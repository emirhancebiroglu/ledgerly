"""T2 — timeout, retry-with-backoff, circuit breaker.

Everything here uses a fake clock and a fake sleep so no test sleeps for real, and a fake inner
``LlmClient`` so no test touches the network.
"""

from __future__ import annotations

import pytest

from app.llm.client import LlmClient, LlmError, VisionPrompt
from app.llm.resilient import (
    CircuitOpenError,
    NonRetryableLlmError,
    ResilientLlmClient,
    RetryableLlmError,
)


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class RecordingSleep:
    def __init__(self) -> None:
        self.calls: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)


class ScriptedLlmClient(LlmClient):
    """Returns/raises whatever is next in a scripted sequence, one call at a time."""

    def __init__(self, script: list) -> None:
        self._script = list(script)
        self.call_count = 0

    @property
    def model_name(self) -> str:
        return "scripted-v1"

    def complete(self, prompt: str) -> str:
        return self._next()

    def complete_vision(self, prompt: VisionPrompt) -> str:
        return self._next()

    def _next(self):
        self.call_count += 1
        outcome = self._script.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def make_client(inner, **overrides):
    clock = overrides.pop("clock", FakeClock())
    sleep = overrides.pop("sleep", RecordingSleep())
    defaults = dict(
        max_retries=2,
        failure_threshold=3,
        cooldown_seconds=10.0,
        base_backoff_seconds=1.0,
        clock=clock,
        sleep=sleep,
    )
    defaults.update(overrides)
    return ResilientLlmClient(inner, **defaults), clock, sleep


def test_a_successful_call_passes_through_untouched():
    inner = ScriptedLlmClient(["ok"])
    client, _, sleep = make_client(inner)

    assert client.complete("hi") == "ok"
    assert sleep.calls == []


def test_a_transient_error_is_retried_with_growing_delay_and_succeeds():
    inner = ScriptedLlmClient([RetryableLlmError("503"), RetryableLlmError("503"), "ok"])
    client, _, sleep = make_client(inner)

    result = client.complete("hi")

    assert result == "ok"
    assert inner.call_count == 3
    assert sleep.calls == [1.0, 2.0]


def test_a_non_retryable_error_is_not_retried():
    inner = ScriptedLlmClient([NonRetryableLlmError("bad request"), "ok"])
    client, _, sleep = make_client(inner)

    with pytest.raises(NonRetryableLlmError):
        client.complete("hi")

    assert inner.call_count == 1
    assert sleep.calls == []


def test_retries_are_capped_and_then_the_error_propagates():
    inner = ScriptedLlmClient([RetryableLlmError("1"), RetryableLlmError("2"), RetryableLlmError("3")])
    client, _, sleep = make_client(inner, max_retries=2)

    with pytest.raises(RetryableLlmError):
        client.complete("hi")

    assert inner.call_count == 3  # initial + 2 retries
    assert sleep.calls == [1.0, 2.0]


def test_the_breaker_opens_after_n_consecutive_failures_and_fails_fast():
    inner = ScriptedLlmClient(
        [RetryableLlmError("1"), RetryableLlmError("1"), RetryableLlmError("1")] * 3
    )
    client, clock, _ = make_client(inner, max_retries=0, failure_threshold=3)

    for _ in range(3):
        with pytest.raises(RetryableLlmError):
            client.complete("hi")

    calls_before_open = inner.call_count

    with pytest.raises(CircuitOpenError):
        client.complete("hi")

    assert inner.call_count == calls_before_open  # provider not called while open


def test_the_breaker_half_opens_after_cooldown_and_a_success_closes_it():
    inner = ScriptedLlmClient(
        [RetryableLlmError("1"), RetryableLlmError("1"), RetryableLlmError("1"), "recovered"]
    )
    client, clock, _ = make_client(inner, max_retries=0, failure_threshold=3, cooldown_seconds=10.0)

    for _ in range(3):
        with pytest.raises(RetryableLlmError):
            client.complete("hi")

    with pytest.raises(CircuitOpenError):
        client.complete("hi")

    clock.advance(10.0)

    assert client.complete("hi") == "recovered"

    # Breaker is closed again: further failures need a fresh streak of `failure_threshold` to open.
    inner._script = [RetryableLlmError("1")]
    with pytest.raises(RetryableLlmError):
        client.complete("hi")


def test_a_half_open_call_that_fails_reopens_the_breaker_immediately():
    inner = ScriptedLlmClient(
        [RetryableLlmError("1"), RetryableLlmError("1"), RetryableLlmError("1"), RetryableLlmError("2")]
    )
    client, clock, _ = make_client(inner, max_retries=0, failure_threshold=3, cooldown_seconds=10.0)

    for _ in range(3):
        with pytest.raises(RetryableLlmError):
            client.complete("hi")

    clock.advance(10.0)

    with pytest.raises(RetryableLlmError):
        client.complete("hi")  # half-open probe fails

    calls_before = inner.call_count
    with pytest.raises(CircuitOpenError):
        client.complete("hi")  # back to open, fails fast

    assert inner.call_count == calls_before


def test_model_name_delegates_to_the_inner_client():
    client, _, _ = make_client(ScriptedLlmClient([]))

    assert client.model_name == "scripted-v1"


def test_no_test_in_this_module_sleeps_for_real(monkeypatch):
    """Guard against a future edit accidentally calling time.sleep directly."""
    import time

    def fail_if_called(*_args, **_kwargs):
        raise AssertionError("real time.sleep was called")

    monkeypatch.setattr(time, "sleep", fail_if_called)

    inner = ScriptedLlmClient([RetryableLlmError("1"), "ok"])
    client, _, _ = make_client(inner)

    assert client.complete("hi") == "ok"
