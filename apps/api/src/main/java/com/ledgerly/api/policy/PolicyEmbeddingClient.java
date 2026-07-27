package com.ledgerly.api.policy;

import java.util.UUID;

/**
 * Port for the `ai` service's {@code POST /embed-policy}.
 *
 * <p>Mirrors {@link com.ledgerly.api.document.ExtractionClient} — an interface rather than a
 * direct HTTP call so the pipeline can be tested against a stub that times out or answers with
 * nonsense.
 */
public interface PolicyEmbeddingClient {

  /**
   * Asks `ai` to chunk and embed a policy document.
   *
   * @return the raw response body, still untrusted and unparsed
   * @throws PolicyEmbeddingUnavailableException if `ai` could not be reached, timed out, or
   *     refused
   */
  String embedPolicy(UUID policyDocumentId, byte[] content, String contentType);
}
