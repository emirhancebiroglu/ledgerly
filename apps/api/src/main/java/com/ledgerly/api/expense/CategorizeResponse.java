package com.ledgerly.api.expense;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** What `ai` returns from {@code POST /categorize}. Mirrors `docs/contracts/categorize-response.schema.json`. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CategorizeResponse(
    @JsonProperty("document_id") String documentId,
    String category,
    double confidence,
    String citation,
    String model) {}
