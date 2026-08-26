package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers what {@link UploadRateLimiter} still owns after M9.9 T1 moved the counting behind {@link
 * RateLimiter}: deriving the key and turning a negative TTL into the endpoint's retry-after.
 *
 * <p>Separate from {@link UploadRateLimiterTest} deliberately. That test drives the real {@link
 * RedisRateLimiter} over a mocked Redis and is the seam proof — it must keep asserting the
 * fail-closed guarantee against the whole stack, so it is left as it was written. Mocking the port
 * is the right tool for the two properties below and the wrong one there.
 */
@ExtendWith(MockitoExtension.class)
class UploadRateLimiterQuotaTest {

  @Mock private RateLimiter rateLimiter;

  @Test
  void exceeded_quota_reports_the_windows_remaining_seconds() {
    when(rateLimiter.acquire(anyString(), anyInt(), anyLong())).thenReturn(-42L);
    UploadRateLimiter limiter = new UploadRateLimiter(rateLimiter, 2, 60, 1, 60);

    assertThatThrownBy(() -> limiter.checkDocumentUpload(UUID.randomUUID()))
        .isInstanceOf(RateLimitExceededException.class)
        .extracting(exception -> ((RateLimitExceededException) exception).getRetryAfterSeconds())
        .isEqualTo(42L);
  }

  /** A shared key would let one upload kind spend the other's quota. */
  @Test
  void document_and_policy_uploads_count_against_separate_keys() {
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.acquire(key.capture(), anyInt(), anyLong())).thenReturn(30L);
    UUID organizationId = UUID.randomUUID();
    UploadRateLimiter limiter = new UploadRateLimiter(rateLimiter, 2, 60, 1, 60);

    limiter.checkDocumentUpload(organizationId);
    limiter.checkPolicyUpload(organizationId);

    assertThat(key.getAllValues())
        .containsExactly(
            "rate-limit:upload:document:" + organizationId,
            "rate-limit:upload:policy:" + organizationId);
  }
}
