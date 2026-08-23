package com.ledgerly.api.policy;

/**
 * Thrown when {@code GET /api/v1/policies} or its chunk sub-resource receives an out-of-range
 * {@code page} or {@code size}, so the API returns 400 rather than a silently clamped page or a
 * 500 from an invalid page request — mirrors {@code InvalidExpenseListQueryException}.
 */
public class InvalidPolicyListQueryException extends RuntimeException {

  public InvalidPolicyListQueryException(String message) {
    super(message);
  }
}
