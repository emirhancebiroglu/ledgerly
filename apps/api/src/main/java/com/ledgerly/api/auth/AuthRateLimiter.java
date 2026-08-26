package com.ledgerly.api.auth;

import com.ledgerly.api.ratelimit.RateLimitExceededException;
import com.ledgerly.api.ratelimit.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Bounds unauthenticated credential attempts without placing an email address in the counter store
 * or in logs.
 */
@Component
public class AuthRateLimiter {

  private final RateLimiter rateLimiter;
  private final byte[] keyMaterial;
  private final int maxRequests;
  private final long windowSeconds;

  public AuthRateLimiter(
      RateLimiter rateLimiter,
      @Value("${ledgerly.auth.jwt-secret}") String jwtSecret,
      @Value("${ledgerly.rate-limit.auth.max-requests:5}") int maxRequests,
      @Value("${ledgerly.rate-limit.auth.window-seconds:60}") long windowSeconds) {
    this.rateLimiter = rateLimiter;
    this.keyMaterial = jwtSecret.getBytes(StandardCharsets.UTF_8);
    Assert.isTrue(maxRequests > 0, "auth rate-limit max-requests must be positive");
    Assert.isTrue(windowSeconds > 0, "auth rate-limit window-seconds must be positive");
    this.maxRequests = maxRequests;
    this.windowSeconds = windowSeconds;
  }

  public void checkLogin(String email) {
    acquire("login", email);
  }

  public void checkRegistration(String email) {
    acquire("register", email);
  }

  private void acquire(String action, String email) {
    long ttl =
        rateLimiter.acquire(
            "rate-limit:auth:" + action + ":" + emailFingerprint(email),
            maxRequests,
            windowSeconds);
    if (ttl < 0) {
      throw new RateLimitExceededException(Math.max(1, -ttl));
    }
  }

  private String emailFingerprint(String email) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 must be available", exception);
    }
  }
}
