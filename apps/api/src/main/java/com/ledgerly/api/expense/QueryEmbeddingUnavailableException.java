package com.ledgerly.api.expense;

/** `ai`'s embed-query endpoint could not be reached, timed out, or refused the request. */
public class QueryEmbeddingUnavailableException extends RuntimeException {

  public QueryEmbeddingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
