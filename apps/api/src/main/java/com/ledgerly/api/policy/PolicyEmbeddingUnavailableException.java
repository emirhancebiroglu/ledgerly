package com.ledgerly.api.policy;

/** `ai`'s embedding endpoint could not be reached, timed out, or refused the request. */
public class PolicyEmbeddingUnavailableException extends RuntimeException {

  public PolicyEmbeddingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
