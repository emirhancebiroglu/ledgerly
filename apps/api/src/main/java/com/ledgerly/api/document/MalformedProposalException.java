package com.ledgerly.api.document;

/** Agent output that does not bind to the agreed proposal contract. */
public class MalformedProposalException extends RuntimeException {

  public MalformedProposalException(String message) {
    super(message);
  }

  public MalformedProposalException(String message, Throwable cause) {
    super(message, cause);
  }
}
