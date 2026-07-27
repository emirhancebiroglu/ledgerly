package com.ledgerly.api.expense;

/** Agent output that does not bind to the agreed response contract. */
public class MalformedAiResponseException extends RuntimeException {

  public MalformedAiResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
