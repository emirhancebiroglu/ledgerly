"""Resilience wrapper around any ``LlmClient``: timeout, retry with backoff, circuit breaker.

Wraps rather than folds into ``LiteLlmClient`` so the raw adapter stays a thin translation to
LiteLLM and every non-retryable-vs-retryable decision lives in one place, testable against a fake
clock and a fake inner client — no test here sleeps for real or touches the network.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum, auto

from app.llm.client import LlmClient, LlmError, VisionPrompt


class RetryableLlmError(LlmError):
    """A transient failure (timeout, 5xx, 429) worth retrying."""


class NonRetryableLlmError(LlmError):
    """A failure that will fail identically on retry (bad request, auth, schema)."""


class CircuitOpenError(LlmError):
    """The breaker is open; the provider was not called."""


class _State(Enum):
    CLOSED = auto()
    OPEN = auto()
    HALF_OPEN = auto()


@dataclass
class _CircuitBreaker:
    failure_threshold: int
    cooldown_seconds: float
    clock: callable
    state: _State = _State.CLOSED
    consecutive_failures: int = 0
    opened_at: float = field(default=0.0)

    def before_call(self) -> None:
        if self.state is _State.OPEN:
            if self.clock() - self.opened_at >= self.cooldown_seconds:
                self.state = _State.HALF_OPEN
            else:
                raise CircuitOpenError("Circuit breaker is open; provider not called")

    def on_success(self) -> None:
        self.state = _State.CLOSED
        self.consecutive_failures = 0

    def on_failure(self) -> None:
        self.consecutive_failures += 1
        if self.state is _State.HALF_OPEN or self.consecutive_failures >= self.failure_threshold:
            self.state = _State.OPEN
            self.opened_at = self.clock()


class ResilientLlmClient(LlmClient):
    """Adds timeout, retry-with-backoff and circuit-breaking to any inner ``LlmClient``.

    The inner client is expected to already translate provider failures into ``LlmError`` (see
    ``LiteLlmClient``); this wrapper only decides which of those get retried, and protects the
    breaker's state from a stream of doomed calls.
    """

    def __init__(
        self,
        inner: LlmClient,
        *,
        max_retries: int,
        failure_threshold: int,
        cooldown_seconds: float,
        base_backoff_seconds: float = 0.5,
        clock: callable = time.monotonic,
        sleep: callable = time.sleep,
        is_retryable: callable | None = None,
    ) -> None:
        self._inner = inner
        self._max_retries = max_retries
        self._base_backoff_seconds = base_backoff_seconds
        self._clock = clock
        self._sleep = sleep
        self._is_retryable = is_retryable or _default_is_retryable
        self._breaker = _CircuitBreaker(
            failure_threshold=failure_threshold, cooldown_seconds=cooldown_seconds, clock=clock
        )

    @property
    def model_name(self) -> str:
        return self._inner.model_name

    def complete(self, prompt: str) -> str:
        return self._call(lambda: self._inner.complete(prompt))

    def complete_vision(self, prompt: VisionPrompt) -> str:
        return self._call(lambda: self._inner.complete_vision(prompt))

    def _call(self, invoke: callable) -> str:
        self._breaker.before_call()

        attempt = 0
        while True:
            try:
                result = invoke()
            except LlmError as error:
                self._breaker.on_failure()
                if not self._is_retryable(error) or attempt >= self._max_retries:
                    raise
                self._sleep(self._base_backoff_seconds * (2**attempt))
                attempt += 1
                continue
            else:
                self._breaker.on_success()
                return result


def _default_is_retryable(error: LlmError) -> bool:
    return isinstance(error, RetryableLlmError)
