package com.ledgerly.api.expense;

import java.util.List;
import java.util.UUID;

/**
 * Port for the `ai` service's {@code POST /categorize}.
 *
 * <p>Mirrors {@link com.ledgerly.api.document.ExtractionClient} — an interface rather than a
 * direct HTTP call so the pipeline can be tested against a stub.
 */
public interface CategorizationClient {

  /**
   * Asks `ai` to classify an expense into one of {@code categories}, given the retrieved policy
   * chunk texts as evidence.
   *
   * @return the raw response body, still untrusted and unparsed
   * @throws CategorizationUnavailableException if `ai` could not be reached, timed out, or
   *     refused
   */
  String categorize(
      UUID documentId,
      String vendor,
      String currency,
      long totalMinor,
      String documentDate,
      List<String> categories,
      List<String> policyChunkTexts);
}
