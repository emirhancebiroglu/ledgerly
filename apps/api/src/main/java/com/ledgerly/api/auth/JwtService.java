package com.ledgerly.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

  private static final String ORG_CLAIM = "org";

  private final SecretKey key;
  private final long accessTokenTtlMinutes;

  public JwtService(
      @Value("${ledgerly.auth.jwt-secret}") String secret,
      @Value("${ledgerly.auth.access-token-ttl-minutes:15}") long accessTokenTtlMinutes) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    this.accessTokenTtlMinutes = accessTokenTtlMinutes;
  }

  public String issueAccessToken(UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim(ORG_CLAIM, organizationId.toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtlMinutes, ChronoUnit.MINUTES)))
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  public UUID organizationIdOf(Claims claims) {
    return UUID.fromString(claims.get(ORG_CLAIM, String.class));
  }

  public UUID userIdOf(Claims claims) {
    return UUID.fromString(claims.getSubject());
  }
}
