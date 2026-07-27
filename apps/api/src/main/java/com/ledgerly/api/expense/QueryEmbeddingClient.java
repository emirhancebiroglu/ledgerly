package com.ledgerly.api.expense;

/** Port for the `ai` service's {@code POST /embed-query} — a query vector for pgvector retrieval. */
public interface QueryEmbeddingClient {

  /**
   * @return the raw response body, still untrusted and unparsed
   * @throws QueryEmbeddingUnavailableException if `ai` could not be reached, timed out, or
   *     refused
   */
  String embedQuery(String text);
}
