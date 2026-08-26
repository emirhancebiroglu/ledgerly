package com.ledgerly.api.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start when {@code ledgerly.rate-limit.backend} names no adapter.
 *
 * <p>Both adapters are selected by {@code @ConditionalOnProperty}, so an unrecognised value —
 * {@code inmemory} for {@code in-memory}, say — matches neither and simply produces no {@link
 * RateLimiter} bean. Nothing else would notice: the limiters are injected by the services that
 * guard uploads and credential attempts, and a missing bean there surfaces as an obscure
 * wiring error far from its cause, or not at all if a future refactor makes the dependency
 * optional. Since these quotas are what stand between a typo and unbounded paid LLM calls, the
 * failure is made explicit and immediate instead.
 */
@Configuration
public class RateLimiterBackendGuard {

  public RateLimiterBackendGuard(
      ObjectProvider<RateLimiter> rateLimiters,
      @Value("${ledgerly.rate-limit.backend:redis}") String configuredBackend) {
    if (rateLimiters.getIfAvailable() == null) {
      throw new IllegalStateException(
          "ledgerly.rate-limit.backend=\"%s\" matches no rate limiter; expected \"redis\" or \"in-memory\""
              .formatted(configuredBackend));
    }
  }
}
