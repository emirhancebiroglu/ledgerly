package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The schema and strict mapper together form the proposal trust boundary. */
class ExtractionProposalParsingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProposalMapper proposalMapper = new ProposalMapper(new ExtractionProposalContract());

  @Test
  void parsesTheGoldenValidExample() throws Exception {
    ExtractionProposal proposal = proposalMapper.parse(validProposal().toString());

    assertThat(proposal.totalMinor()).isEqualTo(12100L);
    assertThat(proposal.taxMinor()).isEqualTo(2100L);
    assertThat(proposal.lineTotalMinor()).isEqualTo(10000L);
    assertThat(proposal.currency()).isEqualTo("EUR");
    assertThat(proposal.invoiceNumber()).isEqualTo("INV-2026-0714");
    assertThat(proposal.model()).isEqualTo("fake-llm-v1");
  }

  @Test
  void parsesAValidRefund() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("total_minor", -12100);
    proposal.put("tax_minor", -2100);
    proposal
        .withArray("lines")
        .forEach(
            line ->
                ((ObjectNode) line).put("amount_minor", -line.path("amount_minor").asLong()));

    assertThat(proposalMapper.parse(proposal.toString()).totalMinor()).isEqualTo(-12100L);
  }

  @Test
  void refusesSchemaViolationsBeforeBinding() throws Exception {
    assertRejected(without("total_minor"));
    assertRejected(without("tax_minor"));
    assertRejected(withoutRequiredConfidence());
    assertRejected(withOutOfRangeConfidence());
    assertRejected(withMalformedDocumentId());
    assertRejected(withMalformedDocumentDate());
    assertRejected(withNegativeQuantity());
    assertRejected(withOverlongVendor());
    assertRejected(withTooManyLines());
    assertRejected(withTooManyWarnings());
  }

  @Test
  void refusesUnknownFieldsAndFractionalMoney() throws Exception {
    ObjectNode unknownField = validProposal();
    unknownField.put("post_directly_to_ledger", true);
    assertRejected(unknownField);

    ObjectNode fractionalAmount = validProposal();
    fractionalAmount.put("total_minor", 12100.5);
    assertRejected(fractionalAmount);
  }

  @Test
  void refusesAStringValueRatherThanCoercingIt() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("invoice_number", 42);

    assertRejected(proposal);
  }

  @Test
  void refusesTrailingTokens() throws Exception {
    assertThatThrownBy(() -> proposalMapper.parse(validProposal().toString() + " {}"))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void refusesAnEmptyExtractionResponseWithoutLeakingAnUncheckedParserException() {
    assertThatThrownBy(() -> proposalMapper.parse(null))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void failureMessagesDoNotEchoTheOffendingContent() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("vendor", "SENSITIVE-VENDOR-NAME");
    proposal.remove("total_minor");

    assertThatThrownBy(() -> proposalMapper.parse(proposal.toString()))
        .isInstanceOf(MalformedProposalException.class)
        .hasMessageNotContaining("SENSITIVE-VENDOR-NAME");
  }

  @Test
  void roundTripsAProposalThroughJson() throws Exception {
    ExtractionProposal original = proposalMapper.parse(validProposal().toString());

    assertThat(proposalMapper.parse(proposalMapper.toJson(original))).isEqualTo(original);
  }

  private static ObjectNode without(String field) throws Exception {
    ObjectNode proposal = validProposal();
    proposal.remove(field);
    return proposal;
  }

  private static ObjectNode withoutRequiredConfidence() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.withObject("confidence").remove("total_minor");
    return proposal;
  }

  private static ObjectNode withOutOfRangeConfidence() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.withObject("confidence").put("total_minor", 1.01);
    return proposal;
  }

  private static ObjectNode withMalformedDocumentId() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("document_id", "not-a-uuid");
    return proposal;
  }

  private static ObjectNode withMalformedDocumentDate() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("document_date", "2026-99-99");
    return proposal;
  }

  private static ObjectNode withNegativeQuantity() throws Exception {
    ObjectNode proposal = validProposal();
    ((ObjectNode) proposal.withArray("lines").get(0)).put("quantity", -1);
    return proposal;
  }

  private static ObjectNode withOverlongVendor() throws Exception {
    ObjectNode proposal = validProposal();
    proposal.put("vendor", "x".repeat(513));
    return proposal;
  }

  private static ObjectNode withTooManyLines() throws Exception {
    ObjectNode proposal = validProposal();
    ArrayNode lines = proposal.withArray("lines");
    ObjectNode line = (ObjectNode) lines.get(0);
    while (lines.size() <= 1000) {
      lines.add(line.deepCopy());
    }
    return proposal;
  }

  private static ObjectNode withTooManyWarnings() throws Exception {
    ObjectNode proposal = validProposal();
    ArrayNode warnings = proposal.withArray("warnings");
    while (warnings.size() <= 50) {
      warnings.add("warning");
    }
    return proposal;
  }

  private static ObjectNode validProposal() throws Exception {
    return (ObjectNode)
        JSON.readTree(Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));
  }

  private void assertRejected(ObjectNode proposal) {
    assertThatThrownBy(() -> proposalMapper.parse(proposal.toString()))
        .isInstanceOf(MalformedProposalException.class);
  }
}
