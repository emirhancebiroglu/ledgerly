"""Fixed-window quota for authenticated calls that can consume model capacity.

Two backends behind one protocol: `RedisRateLimiter` shares a counter across instances,
`InMemoryRateLimiter` keeps it in the process. A shared counter exists to stop one client
spending N quotas by spreading requests over N instances -- at one instance that coordination
has nothing to coordinate, so the in-process adapter enforces the identical limit with no
external dependency. Redis stays the default: `AI_RATE_LIMIT_REDIS_URL` unset or blank selects
in-process, any other value selects Redis, so a multi-instance deployment cannot lose its shared
counter by omitting a setting -- opting out has to be deliberate.
"""

import time
from dataclasses import dataclass
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError

_ACQUIRE_SCRIPT = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
local ttl = redis.call('PTTL', KEYS[1])
local seconds = math.max(1, math.ceil(ttl / 1000))
if count > tonumber(ARGV[1]) then return -seconds end
return seconds
"""


@dataclass(frozen=True)
class RateLimitExceeded(Exception):
    retry_after_seconds: int


class RateLimitUnavailable(Exception):
    pass


class RateLimiter(Protocol):
    """Fixed-window quota counting, independent of where the counter lives.

    Mirrors api's own `RateLimiter` port (M9.9 T1): `acquire` returns the window's remaining
    seconds, negated when this attempt exceeds the quota. Returning rather than raising for the
    admitted case keeps the retry-after arithmetic with the caller that owns the endpoint's
    contract; `RateLimitUnavailable` is the one case that must raise, since a cost-bearing path
    that cannot determine its quota must reject, never admit.
    """

    async def acquire(self, key: str, max_requests: int, window_seconds: int) -> int: ...


class RedisRateLimiter:
    """Redis-backed `RateLimiter`, correct across multiple application instances.

    Reads `PTTL` (milliseconds) and rounds up rather than negating a whole-second `TTL` directly:
    in the final second of a window `TTL` returns 0, and a rejection built as `-ttl` would then be
    `-0` -- which is `0`, admitted by any caller checking the sign. That granted one request past
    the quota at the end of every window on a cost-bearing path (found and fixed the identical bug
    in api's own RedisRateLimiter, M9.9 T2; docs/decisions.md, 2026-08-26).
    """

    def __init__(self, redis_url: str) -> None:
        self._redis = Redis.from_url(redis_url, decode_responses=True)

    async def acquire(self, key: str, max_requests: int, window_seconds: int) -> int:
        try:
            ttl = await self._redis.eval(_ACQUIRE_SCRIPT, 1, key, max_requests, window_seconds)
        except RedisError as error:
            raise RateLimitUnavailable from error

        if ttl is None:
            raise RateLimitUnavailable
        return int(ttl)


class InMemoryRateLimiter:
    """In-process `RateLimiter` for a single-instance deployment.

    Time comes from `time.monotonic()` rather than the wall clock, so a clock adjustment cannot
    extend a window into a lockout or collapse one into a free pass -- the same reasoning as api's
    `InMemoryRateLimiter` (M9.9 T2), which this mirrors.

    Not thread-safe by design rather than by oversight: `acquire`'s dict read-modify-write
    contains no `await`, so on a single event loop it always runs to completion before another
    coroutine can observe it -- asyncio's cooperative scheduling makes this atomic without a lock,
    the same effect api's `ConcurrentHashMap.compute()` gets by a different mechanism. That
    guarantee holds only for the single-worker, single-event-loop deployment this class is built
    for; sharing one instance across OS threads (a threadpool-per-request server, multiple
    uvicorn workers each needing their own instance rather than this one) would break it.

    `ai`'s rate-limited paths are a small, fixed set (`/extract`, `/embed-policy`,
    `/embed-query`, `/categorize`, `/anomaly`), unlike api's per-organization or per-email-
    fingerprint keys -- so unbounded retention here is a handful of entries, not an
    attacker-controlled key space. Swept anyway, on the same call-triggered cadence as api's
    `InMemoryRateLimiter`, so the two implementations stay parallel rather than diverging on the
    one deployment where the keyspace does grow without bound (a future caller keying by
    organization or IP would inherit a sweep already proven, not need to add one under pressure).
    """

    _CALLS_BETWEEN_SWEEPS = 1000

    def __init__(self) -> None:
        # (window start, count, that key's own window length) -- the length travels with the
        # entry rather than coming from the current call's argument, since a sweep triggered by
        # one key's acquire() must not judge a different key's expiry against the wrong window.
        self._windows: dict[str, tuple[float, int, int]] = {}
        self._calls_since_sweep = 0

    async def acquire(self, key: str, max_requests: int, window_seconds: int) -> int:
        now = time.monotonic()

        self._calls_since_sweep += 1
        if self._calls_since_sweep >= self._CALLS_BETWEEN_SWEEPS:
            self._calls_since_sweep = 0
            self._windows = {
                k: v for k, v in self._windows.items() if now - v[0] < v[2]
            }

        start, count, _ = self._windows.get(key, (now, 0, window_seconds))
        if now - start >= window_seconds:
            start, count = now, 0
        count += 1
        self._windows[key] = (start, count, window_seconds)

        remaining = window_seconds - (now - start)
        # Ceiling, floored at 1: a truthful "0 seconds" reads as "retry immediately" while the
        # window is still open, and a negated zero is not negative -- the same hazard the Redis
        # adapter's PTTL switch exists to avoid, reproduced here so both backends agree exactly.
        remaining_seconds = max(1, -int(-remaining // 1))

        return -remaining_seconds if count > max_requests else remaining_seconds


def select_rate_limiter(redis_url: str) -> RateLimiter:
    """Empty/unset `AI_RATE_LIMIT_REDIS_URL` selects the in-process adapter; any other value
    selects Redis. A blank string, not a boolean flag: the URL is already the config surface a
    deployment sets to point at Redis, so leaving it unset is what "opt out" looks like -- no
    second setting to remember."""
    return InMemoryRateLimiter() if not redis_url.strip() else RedisRateLimiter(redis_url)


class AiRateLimiter:
    """Bounds one organization-scoped path's calls into `ai` behind whichever `RateLimiter`
    backend is configured. Key derivation stays here rather than in either backend, mirroring
    api's own split (M9.9 T1) between counting and the caller-owned key/quota contract."""

    def __init__(self, redis_url: str, max_requests: int, window_seconds: int):
        if max_requests <= 0 or window_seconds <= 0:
            raise ValueError("AI rate limit values must be positive")
        self._limiter = select_rate_limiter(redis_url)
        self._max_requests = max_requests
        self._window_seconds = window_seconds

    async def check(self, path: str) -> None:
        ttl = await self._limiter.acquire(
            f"rate-limit:ai:{path}", self._max_requests, self._window_seconds
        )
        if ttl < 0:
            raise RateLimitExceeded(max(1, -ttl))
