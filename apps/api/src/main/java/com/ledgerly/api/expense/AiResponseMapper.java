package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * Parses `ai`'s {@code /categorize} and {@code /embed-query} output. A separate, strict mapper for
 * the same reason as {@link com.ledgerly.api.document.ProposalMapper}: this JSON arrives from
 * something that guesses.
 */
@Component
public class AiResponseMapper {

  private final ObjectMapper objectMapper;

  public AiResponseMapper() {
    this.objectMapper =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  /** @throws MalformedAiResponseException if the JSON does not bind to the agreed shape */
  public CategorizeResponse parseCategorizeResponse(String json) {
    try {
      return objectMapper.readValue(json, CategorizeResponse.class);
    } catch (JsonProcessingException e) {
      throw new MalformedAiResponseException("Categorize response did not match the agreed contract", e);
    }
  }

  /** @throws MalformedAiResponseException if the JSON does not bind to the agreed shape */
  public EmbedQueryResponse parseEmbedQueryResponse(String json) {
    try {
      return objectMapper.readValue(json, EmbedQueryResponse.class);
    } catch (JsonProcessingException e) {
      throw new MalformedAiResponseException("Embed-query response did not match the agreed contract", e);
    }
  }
}
