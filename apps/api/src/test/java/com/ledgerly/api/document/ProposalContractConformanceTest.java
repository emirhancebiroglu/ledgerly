package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What `api` writes must satisfy the shared contract, not merely round-trip through its own mapper.
 *
 * <p>A self-consistent round trip proves nothing about interoperability: the same mapper reading
 * back its own output will happily agree on an encoding the schema forbids. These tests validate
 * the <em>serialized</em> form against {@code docs/contracts/} — the only check that catches the
 * two sides drifting apart.
 */
class ProposalContractConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ExtractionProposalContract proposalContract = new ExtractionProposalContract();
  private final ProposalMapper proposalMapper = new ProposalMapper(proposalContract);

  @Test
  void aProposalSerializedByApiValidatesAgainstTheSharedSchema() throws Exception {
    ExtractionProposal proposal =
        proposalMapper.parse(
            Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));

    String serialized = proposalMapper.toJson(proposal);

    assertThatCode(() -> proposalContract.validate(serialized))
        .as("what api persists and returns must satisfy the contract both services share")
        .doesNotThrowAnyException();
  }

  @Test
  void theSerializedDocumentDateIsAnIsoStringNotAnArray() throws Exception {
    ExtractionProposal proposal =
        proposalMapper.parse(
            Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));

    String serialized = proposalMapper.toJson(proposal);

    // Jackson's default for java.time is a numeric array ([2026,7,14]); the contract says string.
    assertThat(MAPPER.readTree(serialized).get("document_date").isTextual()).isTrue();
    assertThat(MAPPER.readTree(serialized).get("document_date").asText()).isEqualTo("2026-07-14");
  }

  @Test
  void everySerializedMonetaryFieldIsStillAnIntegerAfterRoundTripping() throws Exception {
    ExtractionProposal proposal =
        proposalMapper.parse(
            Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));

    var serialized = MAPPER.readTree(proposalMapper.toJson(proposal));

    assertThat(serialized.get("total_minor").isIntegralNumber()).isTrue();
    assertThat(serialized.get("tax_minor").isIntegralNumber()).isTrue();
    serialized
        .get("lines")
        .forEach(line -> assertThat(line.get("amount_minor").isIntegralNumber()).isTrue());
  }

  @Test
  void everyOptionalFieldCanBeAbsentIndependentlyAndStillRoundTripsThroughPersistence()
      throws Exception {
    for (String optionalField : List.of("vendor", "invoice_number", "warnings", "quantity")) {
      ObjectNode proposal = validProposal();
      removeOptionalField(proposal, optionalField);

      assertAbsentFieldRoundTrips(proposal, optionalField);
    }
  }

  @Test
  void allOptionalFieldsCanBeAbsentTogetherAndStillRoundTripThroughPersistence()
      throws Exception {
    ObjectNode proposal = validProposal();
    for (String optionalField : List.of("vendor", "invoice_number", "warnings", "quantity")) {
      removeOptionalField(proposal, optionalField);
    }

    for (String optionalField : List.of("vendor", "invoice_number", "warnings", "quantity")) {
      assertAbsentFieldRoundTrips(proposal, optionalField);
    }
  }

  private void assertAbsentFieldRoundTrips(ObjectNode rawProposal, String absentField) throws Exception {
    ExtractionProposal parsed = proposalMapper.parse(rawProposal.toString());
    String persisted = proposalMapper.toJson(parsed);

    assertThatCode(() -> proposalContract.validate(persisted)).doesNotThrowAnyException();
    JsonNode stored = MAPPER.readTree(persisted);
    assertThat(optionalFieldNode(stored, absentField)).isNull();
    assertThatCode(() -> proposalMapper.parse(persisted)).doesNotThrowAnyException();
  }

  private ObjectNode validProposal() throws Exception {
    return (ObjectNode)
        MAPPER.readTree(Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));
  }

  private void removeOptionalField(ObjectNode proposal, String optionalField) {
    if (optionalField.equals("quantity")) {
      ((ObjectNode) proposal.withArray("lines").get(0)).remove(optionalField);
      return;
    }
    proposal.remove(optionalField);
  }

  private JsonNode optionalFieldNode(JsonNode proposal, String optionalField) {
    if (optionalField.equals("quantity")) {
      return proposal.path("lines").get(0).get(optionalField);
    }
    return proposal.get(optionalField);
  }
}
