package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The `api` side of the shared contract. This test and the `ai` side's {@code test_contract.py}
 * both load the very same files from {@code docs/contracts/} — neither restates the field list.
 */
class ExtractionContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ExtractionProposalContract proposalContract = new ExtractionProposalContract();

  @Test
  void bothSchemasLoadAtTheirActualOwners() {
    assertThat(proposalContract).isNotNull();
    assertThat(ContractSchemas.load("extract-request.schema.json")).isNotNull();
  }

  @Test
  void theGoldenValidProposalValidatesGreen() throws Exception {
    assertThatCode(
            () ->
                proposalContract.validate(
                    Files.readString(ContractSchemas.example("extraction-proposal.valid.json"))))
        .doesNotThrowAnyException();
  }

  @Test
  void theGoldenValidRequestValidatesGreen() throws Exception {
    assertThat(
            ContractSchemas.load("extract-request.schema.json")
                .validate(
                    MAPPER.readTree(
                        Files.readString(ContractSchemas.example("extract-request.valid.json")))))
        .isEmpty();
  }

  @Test
  void aProposalMissingTotalMinorValidatesRed() throws Exception {
    assertThatThrownBy(
            () ->
                proposalContract.validate(
                    Files.readString(ContractSchemas.example("extraction-proposal.missing-total.json"))))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void aProposalWithAFloatAmountValidatesRed() throws Exception {
    assertThatThrownBy(
            () ->
                proposalContract.validate(
                    Files.readString(ContractSchemas.example("extraction-proposal.float-amount.json"))))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void everyMonetaryFieldInTheSchemaIsDeclaredInteger() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            Files.readString(
                ContractSchemas.contractsDirectory().resolve("extraction-proposal.schema.json")));

    assertThat(schema.at("/properties/total_minor/type").asText()).isEqualTo("integer");
    assertThat(schema.at("/properties/tax_minor/type").asText()).isEqualTo("integer");
    assertThat(schema.at("/$defs/line/properties/amount_minor/type").asText()).isEqualTo("integer");
  }

  @Test
  void theSchemaRequiresPerFieldConfidenceOnEveryExtractedField() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            Files.readString(
                ContractSchemas.contractsDirectory().resolve("extraction-proposal.schema.json")));

    List<String> required = new ArrayList<>();
    schema.at("/properties/confidence/required").forEach(node -> required.add(node.asText()));

    assertThat(required).contains("currency", "total_minor", "tax_minor", "document_date");
  }

  @Test
  void anUnknownTopLevelFieldIsRejected() throws Exception {
    JsonNode proposal =
        MAPPER.readTree(Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal).put("smuggled_field", "surprise");

    assertThatThrownBy(() -> proposalContract.validate(proposal.toString()))
        .isInstanceOf(MalformedProposalException.class);
  }
}
