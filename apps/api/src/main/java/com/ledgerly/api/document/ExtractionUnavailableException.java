package com.ledgerly.api.document;

/** The `ai` service could not be reached or refused to extract the document. */
public class ExtractionUnavailableException extends RuntimeException {

  public ExtractionUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public ExtractionUnavailableException(String message) {
    super(message);
  }
}
