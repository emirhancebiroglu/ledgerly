"""AiRateLimiter's own contract: key derivation, backend selection, and the retry-after
translation -- what the FastAPI-level test_rate_limit.py cannot see since it mocks the whole
`check` protocol rather than exercising this class."""

import asyncio

import pytest

from app.rate_limit import (
    AiRateLimiter,
    InMemoryRateLimiter,
    RateLimitExceeded,
    RateLimitUnavailable,
    RedisRateLimiter,
    select_rate_limiter,
)
import time


def run(coro):
    return asyncio.run(coro)


class FakeLimiter:
    def __init__(self, results):
        self._results = list(results)
        self.calls: list[tuple[str, int, int]] = []

    async def acquire(self, key, max_requests, window_seconds):
        self.calls.append((key, max_requests, window_seconds))
        return self._results.pop(0)


def test_empty_redis_url_selects_the_in_process_backend():
    assert isinstance(select_rate_limiter(""), InMemoryRateLimiter)
    assert isinstance(select_rate_limiter("   "), InMemoryRateLimiter)


def test_a_configured_redis_url_selects_the_redis_backend():
    assert isinstance(select_rate_limiter("redis://localhost:6379/0"), RedisRateLimiter)


def test_the_key_is_scoped_by_path_and_never_carries_a_caller_identity():
    fake = FakeLimiter([30])
    limiter = AiRateLimiter.__new__(AiRateLimiter)
    limiter._limiter = fake
    limiter._max_requests = 5
    limiter._window_seconds = 60

    run(limiter.check("/embed-query"))

    assert fake.calls == [("rate-limit:ai:/embed-query", 5, 60)]


def test_an_admitted_request_raises_nothing():
    fake = FakeLimiter([30])
    limiter = AiRateLimiter.__new__(AiRateLimiter)
    limiter._limiter = fake
    limiter._max_requests = 5
    limiter._window_seconds = 60

    run(limiter.check("/embed-query"))  # must not raise


def test_an_exceeded_quota_raises_with_the_windows_remaining_seconds():
    fake = FakeLimiter([-42])
    limiter = AiRateLimiter.__new__(AiRateLimiter)
    limiter._limiter = fake
    limiter._max_requests = 5
    limiter._window_seconds = 60

    with pytest.raises(RateLimitExceeded) as excinfo:
        run(limiter.check("/embed-query"))
    assert excinfo.value.retry_after_seconds == 42


def test_an_unavailable_quota_rejects_the_cost_bearing_call():
    class UnavailableLimiter:
        async def acquire(self, key, max_requests, window_seconds):
            raise RateLimitUnavailable

    limiter = AiRateLimiter.__new__(AiRateLimiter)
    limiter._limiter = UnavailableLimiter()
    limiter._max_requests = 5
    limiter._window_seconds = 60

    with pytest.raises(RateLimitUnavailable):
        run(limiter.check("/embed-query"))


def test_construction_rejects_non_positive_quota_values():
    with pytest.raises(ValueError):
        AiRateLimiter("", 0, 60)
    with pytest.raises(ValueError):
        AiRateLimiter("", 5, 0)


def test_expired_windows_are_swept_rather_than_retained_forever():
    """ai's keyspace is small and fixed today (one entry per path), but the sweep exists so a
    future caller keying by something unbounded (organization, IP) inherits eviction already
    proven rather than needing to add it under pressure -- mirrors api's InMemoryRateLimiter."""
    limiter = InMemoryRateLimiter()
    limiter._CALLS_BETWEEN_SWEEPS = 5

    for i in range(4):
        run(limiter.acquire(f"stale-key-{i}", 1, 1))
    time.sleep(1.1)  # past every stale key's 1s window
    run(limiter.acquire("trigger-the-sweep", 1, 60))

    assert list(limiter._windows.keys()) == ["trigger-the-sweep"]
