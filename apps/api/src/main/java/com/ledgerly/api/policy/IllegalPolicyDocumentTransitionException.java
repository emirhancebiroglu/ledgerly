package com.ledgerly.api.policy;

/** An attempt to move a policy document to a status its current status does not allow. */
public class IllegalPolicyDocumentTransitionException extends RuntimeException {

  public IllegalPolicyDocumentTransitionException(
      PolicyDocumentStatus from, PolicyDocumentStatus to) {
    super("Illegal policy document status transition: " + from + " -> " + to);
  }
}
