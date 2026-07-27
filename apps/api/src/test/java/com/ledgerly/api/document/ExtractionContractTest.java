package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The `api` side of the shared contract. This test and the `ai` side's {@code test_contract.py}
 * both load the very same files from {@code docs/contracts/} — neither restates the field list.
 */
class ExtractionContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void bothSchemasAreValidJsonSchemaAndLoadFromTheSharedDirectory() {
    assertThat(ContractSchemas.load("extraction-proposal.schema.json")).isNotNull();
    assertThat(ContractSchemas.load("extract-request.schema.json")).isNotNull();
  }

  @Test
  void theGoldenValidProposalValidatesGreen() throws Exception {
    JsonSchema schema = ContractSchemas.load("extraction-proposal.schema.json");

    assertThat(validate(schema, ContractSchemas.example("extraction-proposal.valid.json")))
        .as("the documented valid example must satisfy the schema")
        .isEmpty();
  }

  @Test
  void theGoldenValidRequestValidatesGreen() throws Exception {
    JsonSchema schema = ContractSchemas.load("extract-request.schema.json");

    assertThat(validate(schema, ContractSchemas.example("extract-request.valid.json"))).isEmpty();
  }

  @Test
  void aProposalMissingTotalMinorValidatesRed() throws Exception {
    JsonSchema schema = ContractSchemas.load("extraction-proposal.schema.json");

    assertThat(validate(schema, ContractSchemas.example("extraction-proposal.missing-total.json")))
        .as("total_minor is required")
        .isNotEmpty();
  }

  @Test
  void aProposalWithAFloatAmountValidatesRed() throws Exception {
    JsonSchema schema = ContractSchemas.load("extraction-proposal.schema.json");

    assertThat(validate(schema, ContractSchemas.example("extraction-proposal.float-amount.json")))
        .as("money must be an integer of minor units, never a float")
        .isNotEmpty();
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
    JsonSchema schema = ContractSchemas.load("extraction-proposal.schema.json");
    JsonNode proposal =
        MAPPER.readTree(Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal).put("smuggled_field", "surprise");

    assertThat(schema.validate(proposal))
        .as("additionalProperties is false, so an unexpected field is a contract break")
        .isNotEmpty();
  }

  private java.util.Set<com.networknt.schema.ValidationMessage> validate(
      JsonSchema schema, Path examplePath) throws Exception {
    return schema.validate(MAPPER.readTree(Files.readString(examplePath)));
  }
}
