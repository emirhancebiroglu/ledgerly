package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
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
}
