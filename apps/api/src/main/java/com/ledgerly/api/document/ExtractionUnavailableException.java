package com.ledgerly.api.document;

/** The `ai` service could not be reached, timed out, or returned a transient response. */
public class ExtractionUnavailableException extends RuntimeException {

  public ExtractionUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public ExtractionUnavailableException(String message) {
    super(message);
  }
}
