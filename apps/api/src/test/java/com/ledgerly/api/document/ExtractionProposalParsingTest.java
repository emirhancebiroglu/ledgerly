package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Parsing is itself part of the trust boundary: a proposal whose amounts are not integers must fail
 * here, before any rule gets the chance to round it into something plausible.
 */
class ExtractionProposalParsingTest {

  private final ProposalMapper proposalMapper = new ProposalMapper();

  @Test
  void parsesTheGoldenValidExample() throws Exception {
    ExtractionProposal proposal =
        proposalMapper.parse(
            Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));

    assertThat(proposal.totalMinor()).isEqualTo(12100L);
    assertThat(proposal.taxMinor()).isEqualTo(2100L);
    assertThat(proposal.lineTotalMinor()).isEqualTo(10000L);
    assertThat(proposal.currency()).isEqualTo("EUR");
    assertThat(proposal.invoiceNumber()).isEqualTo("INV-2026-0714");
    assertThat(proposal.model()).isEqualTo("fake-llm-v1");
  }

  @Test
  void refusesAFloatTotalRatherThanTruncatingIt() {
    String floatTotal =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","currency":"EUR",
         "total_minor":121.5,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100}],
         "confidence":{"currency":1.0},"model":"m","warnings":[]}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(floatTotal))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void refusesAFloatLineAmount() {
    String floatLine =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","currency":"EUR",
         "total_minor":121,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100.25}],
         "confidence":{"currency":1.0},"model":"m","warnings":[]}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(floatLine))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void refusesAFloatTaxEvenWhenItWouldTruncateToAConsistentTotal() {
    // 100 + 21.0 == 121 exactly, so a truncating parser would accept this as arithmetically sound.
    String floatTax =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","currency":"EUR",
         "total_minor":121,"tax_minor":21.0,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100}],
         "confidence":{"currency":1.0},"model":"m","warnings":[]}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(floatTax))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void refusesAnUnknownFieldSmuggledIntoTheProposal() {
    String extraField =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","currency":"EUR",
         "total_minor":121,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100}],
         "confidence":{"currency":1.0},"model":"m","warnings":[],
         "post_directly_to_ledger":true}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(extraField))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void acceptsAProposalWithoutAnInvoiceNumberForLegacyDocuments() {
    String noInvoiceNumber =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","currency":"EUR",
         "total_minor":121,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100}],
         "confidence":{"currency":1.0},"model":"m","warnings":[]}
        """;

    assertThat(proposalMapper.parse(noInvoiceNumber).invoiceNumber()).isNull();
  }

  @Test
  void refusesANonStringInvoiceNumberRatherThanCoercingIt() {
    String numericInvoiceNumber =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"V","invoice_number":42,"currency":"EUR",
         "total_minor":121,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[{"description":"a","quantity":1000,"amount_minor":100}],
         "confidence":{"currency":1.0},"model":"m","warnings":[]}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(numericInvoiceNumber))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void refusesOutrightMalformedJson() {
    assertThatThrownBy(() -> proposalMapper.parse("{not json at all"))
        .isInstanceOf(MalformedProposalException.class);
  }

  @Test
  void theFailureMessageDoesNotEchoTheOffendingContent() {
    String withSecret =
        """
        {"document_id":"3f1a8c2e-9b4d-4e7a-8f16-2c5d7e9a1b30","vendor":"SENSITIVE-VENDOR-NAME",
         "currency":"EUR","total_minor":121.5,"tax_minor":21,"document_date":"2026-07-14",
         "lines":[],"confidence":{},"model":"m","warnings":[]}
        """;

    assertThatThrownBy(() -> proposalMapper.parse(withSecret))
        .isInstanceOf(MalformedProposalException.class)
        .hasMessageNotContaining("SENSITIVE-VENDOR-NAME");
  }

  @Test
  void roundTripsAProposalThroughJson() throws Exception {
    ExtractionProposal original =
        proposalMapper.parse(
            Files.readString(ContractSchemas.example("extraction-proposal.valid.json")));

    assertThat(proposalMapper.parse(proposalMapper.toJson(original))).isEqualTo(original);
  }
}
