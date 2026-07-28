package com.ledgerly.api.ratelimit;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Redis-backed fixed-window quotas for user actions that can invoke paid AI services. */
@Component
public class UploadRateLimiter {

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
  private final Quota documentQuota;
  private final Quota policyQuota;

  public UploadRateLimiter(
      StringRedisTemplate redisTemplate,
      @Value("${ledgerly.rate-limit.document-upload.max-requests:10}") int documentMaxRequests,
      @Value("${ledgerly.rate-limit.document-upload.window-seconds:60}") long documentWindowSeconds,
      @Value("${ledgerly.rate-limit.policy-upload.max-requests:2}") int policyMaxRequests,
      @Value("${ledgerly.rate-limit.policy-upload.window-seconds:60}") long policyWindowSeconds) {
    this.redisTemplate = redisTemplate;
    this.documentQuota = new Quota(documentMaxRequests, documentWindowSeconds);
    this.policyQuota = new Quota(policyMaxRequests, policyWindowSeconds);
  }

  public void checkDocumentUpload(UUID organizationId) {
    acquire("document", organizationId, documentQuota);
  }

  public void checkPolicyUpload(UUID organizationId) {
    acquire("policy", organizationId, policyQuota);
  }

  private void acquire(String resource, UUID organizationId, Quota quota) {
    try {
      Long ttl =
          redisTemplate.execute(
              ACQUIRE_SCRIPT,
              List.of("rate-limit:upload:" + resource + ":" + organizationId),
              Integer.toString(quota.maxRequests()),
              Long.toString(quota.windowSeconds()));
      if (ttl == null) {
        throw new RateLimitUnavailableException(new IllegalStateException("Redis returned no quota result"));
      }
      if (ttl < 0) {
        throw new RateLimitExceededException(Math.max(1, -ttl));
      }
    } catch (DataAccessException exception) {
      throw new RateLimitUnavailableException(exception);
    }
  }

  private record Quota(int maxRequests, long windowSeconds) {
    private Quota {
      Assert.isTrue(maxRequests > 0, "rate-limit max-requests must be positive");
      Assert.isTrue(windowSeconds > 0, "rate-limit window-seconds must be positive");
    }
  }
}
