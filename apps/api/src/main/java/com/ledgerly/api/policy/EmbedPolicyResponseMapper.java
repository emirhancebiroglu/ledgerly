package com.ledgerly.api.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * Parses `ai`'s {@code /embed-policy} output. A separate, strict mapper for the same reason as
 * {@link com.ledgerly.api.document.ProposalMapper}: this JSON arrives from something that guesses.
 */
@Component
public class EmbedPolicyResponseMapper {

  private final ObjectMapper objectMapper;

  public EmbedPolicyResponseMapper() {
    this.objectMapper =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  /**
   * @throws MalformedEmbedPolicyResponseException if the JSON does not bind to the agreed shape
   */
  public EmbedPolicyResponse parse(String json) {
    try {
      return objectMapper.readValue(json, EmbedPolicyResponse.class);
    } catch (JsonProcessingException e) {
      throw new MalformedEmbedPolicyResponseException(
          "Embed-policy response did not match the agreed contract", e);
    }
  }
}
