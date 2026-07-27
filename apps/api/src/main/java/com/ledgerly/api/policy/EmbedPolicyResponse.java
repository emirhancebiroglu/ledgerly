package com.ledgerly.api.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What `ai` returns from {@code POST /embed-policy}. Mirrors
 * {@code docs/contracts/embed-policy-response.schema.json}.
 *
 * <p>Nothing here is trusted beyond its shape — {@code embedding_dimensions} is read from the
 * response itself rather than assumed, since the embedding model (and therefore its dimension) is
 * `ai`'s configuration choice, not `api`'s.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EmbedPolicyResponse(
    @JsonProperty("policy_document_id") String policyDocumentId,
    String model,
    @JsonProperty("embedding_dimensions") int embeddingDimensions,
    List<Chunk> chunks) {

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Chunk(
      @JsonProperty("chunk_index") int chunkIndex,
      @JsonProperty("chunk_text") String chunkText,
      List<Double> embedding) {}
}
