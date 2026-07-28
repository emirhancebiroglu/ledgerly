package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Both services load these M8 anomaly contracts directly from {@code docs/contracts/}. */
class AnomalyContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void sharedRequestAndResponseSchemasLoadAndValidateTheirGoldenExamples() throws Exception {
    assertValid("anomaly-request.schema.json", "anomaly-request.valid.json");
    assertValid("anomaly-response.schema.json", "anomaly-response.valid.json");
  }

  @Test
  void requestRejectsFractionalMoneyAndUnknownFields() throws Exception {
    JsonSchema schema = ContractSchemas.load("anomaly-request.schema.json");
    JsonNode request = readExample("anomaly-request.valid.json");

    ((ObjectNode) request).put("amount_minor", 12.5);
    assertThat(schema.validate(request)).isNotEmpty();

    request = readExample("anomaly-request.valid.json");
    ((ObjectNode) request).put("unexpected", true);
    assertThat(schema.validate(request)).isNotEmpty();
  }

  private void assertValid(String schemaFile, String exampleFile) throws Exception {
    assertThat(ContractSchemas.load(schemaFile).validate(readExample(exampleFile))).isEmpty();
  }

  private JsonNode readExample(String exampleFile) throws Exception {
    return MAPPER.readTree(Files.readString(ContractSchemas.example(exampleFile)));
  }
}
