"""Redis-backed quota for authenticated calls that can consume model capacity."""

from dataclasses import dataclass

from redis.asyncio import Redis
from redis.exceptions import RedisError

_ACQUIRE_SCRIPT = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
local ttl = redis.call('TTL', KEYS[1])
if count > tonumber(ARGV[1]) then return -ttl end
return ttl
"""


@dataclass(frozen=True)
class RateLimitExceeded(Exception):
    retry_after_seconds: int


class RateLimitUnavailable(Exception):
    pass


class AiRateLimiter:
    def __init__(self, redis_url: str, max_requests: int, window_seconds: int):
        if max_requests <= 0 or window_seconds <= 0:
            raise ValueError("AI rate limit values must be positive")
        self._redis = Redis.from_url(redis_url, decode_responses=True)
        self._max_requests = max_requests
        self._window_seconds = window_seconds

    async def check(self, path: str) -> None:
        try:
            ttl = await self._redis.eval(
                _ACQUIRE_SCRIPT,
                1,
                f"rate-limit:ai:{path}",
                self._max_requests,
                self._window_seconds,
            )
        except RedisError as error:
            raise RateLimitUnavailable from error

        if ttl is None:
            raise RateLimitUnavailable
        if int(ttl) < 0:
            raise RateLimitExceeded(max(1, -int(ttl)))
