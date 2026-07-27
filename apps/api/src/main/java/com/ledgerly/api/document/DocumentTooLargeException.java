package com.ledgerly.api.document;

/** The upload exceeds the configured size cap. */
public class DocumentTooLargeException extends RuntimeException {

  public DocumentTooLargeException(String message) {
    super(message);
  }
}
