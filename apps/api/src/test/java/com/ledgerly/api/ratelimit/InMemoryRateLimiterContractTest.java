package com.ledgerly.api.ratelimit;

/** Runs the shared {@link RateLimiterContract} against the in-process adapter. */
class InMemoryRateLimiterContractTest extends RateLimiterContract {

  private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

  @Override
  protected RateLimiter limiter() {
    return limiter;
  }
}
