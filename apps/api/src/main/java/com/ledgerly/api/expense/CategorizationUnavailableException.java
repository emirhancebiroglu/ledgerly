package com.ledgerly.api.expense;

/** `ai`'s categorization endpoint could not be reached, timed out, or refused the request. */
public class CategorizationUnavailableException extends RuntimeException {

  public CategorizationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
