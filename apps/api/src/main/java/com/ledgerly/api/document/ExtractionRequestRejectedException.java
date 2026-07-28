package com.ledgerly.api.document;

/** The agent rejected a request permanently (for example, a bad internal service credential). */
public class ExtractionRequestRejectedException extends RuntimeException {

  public ExtractionRequestRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
