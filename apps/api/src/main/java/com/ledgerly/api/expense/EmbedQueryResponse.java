package com.ledgerly.api.expense;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** What `ai` returns from {@code POST /embed-query}. Mirrors `docs/contracts/embed-query-response.schema.json`. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EmbedQueryResponse(
    String model, @JsonProperty("embedding_dimensions") int embeddingDimensions, List<Double> embedding) {}
