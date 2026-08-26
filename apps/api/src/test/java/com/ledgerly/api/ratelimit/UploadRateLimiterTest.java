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

@ExtendWith(MockitoExtension.class)
class UploadRateLimiterTest {

  @Mock private RateLimiter rateLimiter;

  /**
   * M9.9 T1 moved the Redis call behind {@link RateLimiter}, so the outage arrives here as the
   * exception the port declares rather than as a {@code RedisConnectionFailureException} the
   * limiter translates itself. What is being asserted is unchanged and is the point of the test:
   * an unavailable quota must reject a cost-bearing upload rather than admit it.
   */
  @Test
  void unavailable_quota_rejects_cost_bearing_uploads() {
    when(rateLimiter.acquire(anyString(), anyInt(), anyLong()))
        .thenThrow(new RateLimitUnavailableException(new IllegalStateException("unavailable")));
    UploadRateLimiter limiter = new UploadRateLimiter(rateLimiter, 2, 60, 1, 60);

    assertThatThrownBy(() -> limiter.checkDocumentUpload(UUID.randomUUID()))
        .isInstanceOf(RateLimitUnavailableException.class);
  }

  @Test
  void exceeded_quota_reports_the_windows_remaining_seconds() {
    when(rateLimiter.acquire(anyString(), anyInt(), anyLong())).thenReturn(-42L);
    UploadRateLimiter limiter = new UploadRateLimiter(rateLimiter, 2, 60, 1, 60);

    assertThatThrownBy(() -> limiter.checkDocumentUpload(UUID.randomUUID()))
        .isInstanceOf(RateLimitExceededException.class)
        .extracting(exception -> ((RateLimitExceededException) exception).getRetryAfterSeconds())
        .isEqualTo(42L);
  }

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
