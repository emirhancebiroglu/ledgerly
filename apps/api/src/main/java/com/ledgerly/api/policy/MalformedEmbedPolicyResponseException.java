package com.ledgerly.api.policy;

/** Agent output that does not bind to the agreed embed-policy response contract. */
public class MalformedEmbedPolicyResponseException extends RuntimeException {

  public MalformedEmbedPolicyResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
