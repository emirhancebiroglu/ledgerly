"""The behavior every RateLimiter must exhibit, run against both adapters -- mirrors api's
RateLimiterContract (M9.9 T1/T2) for the same reason: swapping backends must not change what a
caller observes.

RedisRateLimiter's cases require a real Redis (a mock would only prove the test calls the same
methods the implementation does, not that the Lua script's arithmetic is correct -- the exact gap
that hid the -0 admission bug on api's side until a real-Redis contract test caught it). CI
provides one as a service container; skipped locally if none is reachable.

Written as sync test functions wrapping asyncio.run() rather than adding pytest-asyncio: no test
in this suite awaits anything today, and one file's worth of async calls doesn't justify a new
test dependency.
"""

import asyncio
import uuid

import pytest
import redis
from redis.exceptions import RedisError

from app.rate_limit import InMemoryRateLimiter, RateLimitUnavailable, RedisRateLimiter

REDIS_URL = "redis://localhost:6379/0"


def _redis_reachable() -> bool:
    try:
        redis.from_url(REDIS_URL).ping()
        return True
    except RedisError:
        return False


requires_redis = pytest.mark.skipif(
    not _redis_reachable(), reason="no reachable Redis for RedisRateLimiter's contract cases"
)


def fresh_key() -> str:
    return f"contract-test:{uuid.uuid4()}"


def run(coro):
    return asyncio.run(coro)


async def _requests_within_the_quota_are_admitted(limiter):
    key = fresh_key()
    for _ in range(3):
        assert await limiter.acquire(key, 3, 60) > 0


async def _the_attempt_after_the_quota_is_rejected(limiter):
    key = fresh_key()
    for _ in range(3):
        await limiter.acquire(key, 3, 60)
    assert await limiter.acquire(key, 3, 60) < 0


async def _a_rejection_at_the_top_of_the_window_reports_the_full_window(limiter):
    key = fresh_key()
    assert await limiter.acquire(key, 1, 60) == 60
    assert await limiter.acquire(key, 1, 60) == -60


async def _separate_keys_hold_separate_quotas(limiter):
    exhausted, untouched = fresh_key(), fresh_key()
    await limiter.acquire(exhausted, 1, 60)
    await limiter.acquire(exhausted, 1, 60)
    assert await limiter.acquire(untouched, 1, 60) > 0


async def _a_window_resets_once_it_expires(limiter):
    key = fresh_key()
    await limiter.acquire(key, 1, 1)
    assert await limiter.acquire(key, 1, 1) < 0
    await asyncio.sleep(1.5)
    assert await limiter.acquire(key, 1, 1) > 0


async def _a_rejection_deep_in_a_window_is_still_a_rejection(limiter):
    """A truthful '0 seconds remaining' negated is -0, which is 0 -- not negative, so a caller
    checking the sign would admit the request. This is the boundary the api-side -0 bug lived
    in (docs/decisions.md, 2026-08-26); both adapters must reject here regardless."""
    key = fresh_key()
    await limiter.acquire(key, 1, 10)
    await asyncio.sleep(9.5)
    assert await limiter.acquire(key, 1, 10) < 0


class TestInMemoryRateLimiterContract:
    def limiter(self):
        return InMemoryRateLimiter()

    def test_requests_within_the_quota_are_admitted(self):
        run(_requests_within_the_quota_are_admitted(self.limiter()))

    def test_the_attempt_after_the_quota_is_rejected(self):
        run(_the_attempt_after_the_quota_is_rejected(self.limiter()))

    def test_a_rejection_at_the_top_of_the_window_reports_the_full_window(self):
        run(_a_rejection_at_the_top_of_the_window_reports_the_full_window(self.limiter()))

    def test_separate_keys_hold_separate_quotas(self):
        run(_separate_keys_hold_separate_quotas(self.limiter()))

    def test_a_window_resets_once_it_expires(self):
        run(_a_window_resets_once_it_expires(self.limiter()))

    def test_a_rejection_deep_in_a_window_is_still_a_rejection(self):
        run(_a_rejection_deep_in_a_window_is_still_a_rejection(self.limiter()))


async def _with_limiter(redis_url, case):
    """asyncio.run() opens and closes a fresh event loop per test; a redis.asyncio connection
    left open past that point tries to close itself against an already-closed loop during
    garbage collection, which pytest reports as a noisy but harmless unraisable-exception
    warning. Closing explicitly here keeps the suite's output clean."""
    limiter = RedisRateLimiter(redis_url)
    try:
        await case(limiter)
    finally:
        await limiter._redis.aclose()


@requires_redis
class TestRedisRateLimiterContract:
    def test_requests_within_the_quota_are_admitted(self):
        run(_with_limiter(REDIS_URL, _requests_within_the_quota_are_admitted))

    def test_the_attempt_after_the_quota_is_rejected(self):
        run(_with_limiter(REDIS_URL, _the_attempt_after_the_quota_is_rejected))

    def test_a_rejection_at_the_top_of_the_window_reports_the_full_window(self):
        run(_with_limiter(REDIS_URL, _a_rejection_at_the_top_of_the_window_reports_the_full_window))

    def test_separate_keys_hold_separate_quotas(self):
        run(_with_limiter(REDIS_URL, _separate_keys_hold_separate_quotas))

    def test_a_window_resets_once_it_expires(self):
        run(_with_limiter(REDIS_URL, _a_window_resets_once_it_expires))

    def test_a_rejection_deep_in_a_window_is_still_a_rejection(self):
        run(_with_limiter(REDIS_URL, _a_rejection_deep_in_a_window_is_still_a_rejection))

    def test_a_connection_failure_raises_unavailable_not_a_silent_admission(self):
        async def check(limiter):
            with pytest.raises(RateLimitUnavailable):
                await limiter.acquire(fresh_key(), 1, 60)

        run(_with_limiter("redis://127.0.0.1:1/0", check))
