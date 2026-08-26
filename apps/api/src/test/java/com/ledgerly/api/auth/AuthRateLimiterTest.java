package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ledgerly.api.ratelimit.RateLimitExceededException;
import com.ledgerly.api.ratelimit.RateLimitUnavailableException;
import com.ledgerly.api.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

  private static final String JWT_SECRET = "test-secret-material-for-the-fingerprint-hmac";

  @Mock private RateLimiter rateLimiter;

  /**
   * The counter key must never carry the address itself: an email in a key is an email in every
   * keyspace dump, {@code MONITOR} trace and store backup. {@code AuthEndpointsIT} only asserts
   * that a 429 eventually arrives, which would stay green if the fingerprint were replaced by the
   * raw address — so the format is pinned here, where a regression is visible.
   */
  @Test
  void the_key_carries_an_hmac_fingerprint_and_never_the_email_itself() {
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.acquire(key.capture(), anyInt(), anyLong())).thenReturn(30L);

    limiter().checkLogin("Someone@Example.com");

    assertThat(key.getValue()).matches("rate-limit:auth:login:[0-9a-f]{64}");
    assertThat(key.getValue()).doesNotContainIgnoringCase("example.com");
  }

  /** Trimming and lower-casing keep one address from spending another spelling's quota. */
  @Test
  void the_fingerprint_ignores_surrounding_space_and_case() {
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.acquire(key.capture(), anyInt(), anyLong())).thenReturn(30L);
    AuthRateLimiter limiter = limiter();

    limiter.checkLogin("  Someone@Example.com  ");
    limiter.checkLogin("someone@example.com");

    assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
  }

  @Test
  void login_and_registration_count_against_separate_keys() {
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.acquire(key.capture(), anyInt(), anyLong())).thenReturn(30L);
    AuthRateLimiter limiter = limiter();

    limiter.checkLogin("someone@example.com");
    limiter.checkRegistration("someone@example.com");

    assertThat(key.getAllValues().get(0)).startsWith("rate-limit:auth:login:");
    assertThat(key.getAllValues().get(1)).startsWith("rate-limit:auth:register:");
  }

  @Test
  void exceeded_quota_reports_the_windows_remaining_seconds() {
    when(rateLimiter.acquire(anyString(), anyInt(), anyLong())).thenReturn(-42L);

    assertThatThrownBy(() -> limiter().checkLogin("someone@example.com"))
        .isInstanceOf(RateLimitExceededException.class)
        .extracting(exception -> ((RateLimitExceededException) exception).getRetryAfterSeconds())
        .isEqualTo(42L);
  }

  /** Credential stuffing must not become unlimited the moment the counter store is unreachable. */
  @Test
  void unavailable_quota_rejects_the_attempt() {
    when(rateLimiter.acquire(anyString(), anyInt(), anyLong()))
        .thenThrow(new RateLimitUnavailableException(new IllegalStateException("unavailable")));

    assertThatThrownBy(() -> limiter().checkLogin("someone@example.com"))
        .isInstanceOf(RateLimitUnavailableException.class);
  }

  private AuthRateLimiter limiter() {
    return new AuthRateLimiter(rateLimiter, JWT_SECRET, 5, 60);
  }
}
