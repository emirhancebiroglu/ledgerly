package com.ledgerly.api.document;

import java.util.UUID;

/**
 * Port for the `ai` service's {@code POST /extract}.
 *
 * <p>An interface rather than a direct HTTP call so the pipeline can be tested against a stub that
 * times out or answers with nonsense — the failure modes that matter most and are hardest to
 * provoke against a real service.
 */
public interface ExtractionClient {

  /**
   * Asks `ai` to read a document.
   *
   * @return the raw response body, still untrusted and unparsed
   * @throws ExtractionUnavailableException if `ai` could not be reached, timed out, or refused
   */
  String extract(UUID documentId, byte[] content, String contentType, String filename);
}
