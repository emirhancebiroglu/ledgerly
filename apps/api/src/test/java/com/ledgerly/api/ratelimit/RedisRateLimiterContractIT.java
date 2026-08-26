package com.ledgerly.api.ratelimit;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the shared {@link RateLimiterContract} against a real Redis, so the two adapters are held to
 * one standard by execution rather than by inspection. Mocking Redis here would prove only that the
 * adapter calls the script it was written to call, not that the script enforces the quota.
 *
 * <p>Its own container rather than {@link com.ledgerly.api.ledger.AbstractPostgresIT}'s: this needs
 * no Spring context and no database, and borrowing that base class would start Postgres and boot an
 * application for a test that exercises one class.
 */
@Tag("integration")
class RedisRateLimiterContractIT extends RateLimiterContract {

  private static final RedisContainer REDIS =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static RedisRateLimiter limiter;

  static {
    REDIS.start();
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    limiter = new RedisRateLimiter(new StringRedisTemplate(connectionFactory));
  }

  @Override
  protected RateLimiter limiter() {
    return limiter;
  }
}
