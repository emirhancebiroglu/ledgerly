package com.ledgerly.api.anomaly;

/** An advisory dependency failed; callers deliberately continue posting without an advisory. */
public class AnomalyUnavailableException extends RuntimeException {
  public AnomalyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
