package com.ledgerly.api.auth;

import com.ledgerly.api.ratelimit.RateLimitExceededException;
import com.ledgerly.api.ratelimit.RateLimitUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Bounds unauthenticated credential attempts without placing an email address in Redis or logs. */
@Component
public class AuthRateLimiter {

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
  private final byte[] keyMaterial;
  private final int maxRequests;
  private final long windowSeconds;

  public AuthRateLimiter(
      StringRedisTemplate redisTemplate,
      @Value("${ledgerly.auth.jwt-secret}") String jwtSecret,
      @Value("${ledgerly.rate-limit.auth.max-requests:5}") int maxRequests,
      @Value("${ledgerly.rate-limit.auth.window-seconds:60}") long windowSeconds) {
    this.redisTemplate = redisTemplate;
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
    try {
      Long ttl =
          redisTemplate.execute(
              ACQUIRE_SCRIPT,
              List.of("rate-limit:auth:" + action + ":" + emailFingerprint(email)),
              Integer.toString(maxRequests),
              Long.toString(windowSeconds));
      if (ttl == null) {
        throw new RateLimitUnavailableException(
            new IllegalStateException("Redis returned no quota result"));
      }
      if (ttl < 0) {
        throw new RateLimitExceededException(Math.max(1, -ttl));
      }
    } catch (DataAccessException exception) {
      throw new RateLimitUnavailableException(exception);
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
