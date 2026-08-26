package com.ledgerly.api.ratelimit;

/**
 * Fixed-window quota counting, independent of where the counter lives.
 *
 * <p>Extracted at M9.9 because {@link UploadRateLimiter} and {@link
 * com.ledgerly.api.auth.AuthRateLimiter} ran a byte-identical Lua script against Redis and differed
 * only in how they derive a key. Key derivation stays with each caller — the upload limiter scopes
 * by organization, the auth limiter by an HMAC fingerprint of an email that must never reach a
 * counter store in plaintext — while the counting itself moves behind this port so a deployment
 * that runs a single instance can satisfy it in-process.
 */
public interface RateLimiter {

  /**
   * Records one attempt against {@code key} and reports the window's remaining seconds.
   *
   * @param key the caller-derived counter identity; callers are responsible for namespacing it so
   *     two unrelated quotas cannot collide
   * @param maxRequests attempts permitted within one window; must be positive
   * @param windowSeconds window length in seconds; must be positive
   * @return seconds remaining in the current window when the attempt is admitted, or the negated
   *     remaining seconds when this attempt exceeds the quota. Returning rather than throwing keeps
   *     the retry-after arithmetic and the exception's phrasing with the caller that owns the
   *     endpoint's contract.
   * @throws RateLimitUnavailableException when the quota cannot be determined at all. Callers on a
   *     cost-bearing path must treat this as a rejection: failing open here would let an outage
   *     become unlimited paid AI calls.
   */
  long acquire(String key, int maxRequests, long windowSeconds);
}
