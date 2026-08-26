package com.ledgerly.api.ratelimit;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link RateLimiter}, correct across multiple application instances because the
 * counter lives outside any one of them.
 *
 * <p>The script is atomic by necessity rather than by preference: {@code INCR} followed by a
 * separate {@code EXPIRE} would let two callers interleave between the two commands and leave a key
 * with no expiry, turning a one-minute window into a permanent lockout.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

  private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
          local ttl = redis.call('TTL', KEYS[1])
          if count > tonumber(ARGV[1]) then return -ttl end
          return ttl
          """,
          Long.class);

  private final StringRedisTemplate redisTemplate;

  public RedisRateLimiter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public long acquire(String key, int maxRequests, long windowSeconds) {
    try {
      Long ttl =
          redisTemplate.execute(
              ACQUIRE_SCRIPT,
              List.of(key),
              Integer.toString(maxRequests),
              Long.toString(windowSeconds));
      if (ttl == null) {
        throw new RateLimitUnavailableException(
            new IllegalStateException("Redis returned no quota result"));
      }
      return ttl;
    } catch (DataAccessException exception) {
      throw new RateLimitUnavailableException(exception);
    }
  }
}
