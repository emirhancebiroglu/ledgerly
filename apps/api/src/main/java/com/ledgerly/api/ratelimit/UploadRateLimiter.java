package com.ledgerly.api.ratelimit;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Fixed-window quotas for user actions that can invoke paid AI services. */
@Component
public class UploadRateLimiter {

  private final RateLimiter rateLimiter;
  private final Quota documentQuota;
  private final Quota policyQuota;

  public UploadRateLimiter(
      RateLimiter rateLimiter,
      @Value("${ledgerly.rate-limit.document-upload.max-requests:10}") int documentMaxRequests,
      @Value("${ledgerly.rate-limit.document-upload.window-seconds:60}") long documentWindowSeconds,
      @Value("${ledgerly.rate-limit.policy-upload.max-requests:2}") int policyMaxRequests,
      @Value("${ledgerly.rate-limit.policy-upload.window-seconds:60}") long policyWindowSeconds) {
    this.rateLimiter = rateLimiter;
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
    long ttl =
        rateLimiter.acquire(
            "rate-limit:upload:" + resource + ":" + organizationId,
            quota.maxRequests(),
            quota.windowSeconds());
    if (ttl < 0) {
      throw new RateLimitExceededException(Math.max(1, -ttl));
    }
  }

  private record Quota(int maxRequests, long windowSeconds) {
    private Quota {
      Assert.isTrue(maxRequests > 0, "rate-limit max-requests must be positive");
      Assert.isTrue(windowSeconds > 0, "rate-limit window-seconds must be positive");
    }
  }
}
