package com.ledgerly.api.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * Parses agent output into an {@link ExtractionProposal}.
 *
 * <p>This mapper is deliberately separate from the application's general-purpose one and is
 * configured far more strictly, because its input is the only JSON in the system that arrives from
 * something that guesses. Two settings matter:
 *
 * <ul>
 *   <li>A fractional number offered for an integer field is <em>refused</em>, not truncated.
 *       Jackson's default would read {@code 121.5} into a {@code long} as {@code 121} — silently
 *       turning a malformed amount into a plausible wrong one, which is precisely the corruption
 *       constraint C1 exists to prevent.
 *   <li>Unknown fields are refused, so a future contract change cannot be quietly ignored on this
 *       side.
 * </ul>
 */
@Component
public class ProposalMapper {

  private final ObjectMapper objectMapper;

  public ProposalMapper() {
    this.objectMapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            // Jackson's default writes a LocalDate as a numeric array ([2026,7,14]); the shared
            // contract says an ISO-8601 string. Without this, what api persists and returns fails
            // the very schema both services are supposed to agree on.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .withCoercionConfig(
                LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .build();
  }

  /**
   * @throws MalformedProposalException if the JSON does not bind to a well-formed proposal
   */
  public ExtractionProposal parse(String json) {
    try {
      return objectMapper.readValue(json, ExtractionProposal.class);
    } catch (JsonProcessingException e) {
      // The message is not propagated to the caller: it can quote the offending document content.
      throw new MalformedProposalException("Proposal did not match the agreed contract", e);
    }
  }

  /** Serializes a proposal for storage on the document row. */
  public String toJson(ExtractionProposal proposal) {
    try {
      return objectMapper.writeValueAsString(proposal);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize extraction proposal", e);
    }
  }
}
