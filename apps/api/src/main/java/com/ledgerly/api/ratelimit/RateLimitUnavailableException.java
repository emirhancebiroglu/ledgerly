package com.ledgerly.api.ratelimit;

/** Redis is unavailable, so a cost-bearing request is rejected rather than risking provider spend. */
public class RateLimitUnavailableException extends RuntimeException {

  public RateLimitUnavailableException(Throwable cause) {
    super("Rate limiting is temporarily unavailable", cause);
  }
}
