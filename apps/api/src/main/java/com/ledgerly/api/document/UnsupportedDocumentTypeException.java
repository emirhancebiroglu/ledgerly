package com.ledgerly.api.document;

/** The uploaded bytes are not a document type this service accepts. */
public class UnsupportedDocumentTypeException extends RuntimeException {

  public UnsupportedDocumentTypeException(String message) {
    super(message);
  }
}
