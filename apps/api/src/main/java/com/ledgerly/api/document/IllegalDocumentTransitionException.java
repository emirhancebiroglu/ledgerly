package com.ledgerly.api.document;

/** An attempt to move a document to a status its current status does not allow. */
public class IllegalDocumentTransitionException extends RuntimeException {

  public IllegalDocumentTransitionException(DocumentStatus from, DocumentStatus to) {
    super("Illegal document status transition: " + from + " -> " + to);
  }
}
