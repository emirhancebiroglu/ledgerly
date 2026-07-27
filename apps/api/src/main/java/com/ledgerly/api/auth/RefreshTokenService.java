package com.ledgerly.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenService {

  private final SecureRandom random = new SecureRandom();
  private final RefreshTokenRepository refreshTokenRepository;
  private final long refreshTokenTtlDays;

  public RefreshTokenService(
      RefreshTokenRepository refreshTokenRepository,
      @Value("${ledgerly.auth.refresh-token-ttl-days:30}") long refreshTokenTtlDays) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.refreshTokenTtlDays = refreshTokenTtlDays;
  }

  public String issue(UUID userId) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    RefreshToken record =
        new RefreshToken(
            userId, hash(rawToken), Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS));
    refreshTokenRepository.save(record);
    return rawToken;
  }

  /** Validates, revokes, and rotates a raw refresh token. Throws if unusable or unknown. */
  public RefreshToken redeem(String rawToken) {
    RefreshToken record =
        refreshTokenRepository
            .findByTokenHash(hash(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
    if (!record.isUsable(Instant.now())) {
      throw new InvalidRefreshTokenException();
    }
    record.revoke();
    refreshTokenRepository.save(record);
    return record;
  }

  private String hash(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hashed);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
