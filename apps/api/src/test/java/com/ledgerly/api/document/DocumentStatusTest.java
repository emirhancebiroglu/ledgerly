package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentStatusTest {

  @Test
  void aNewDocumentStartsPending() {
    assertThat(newDocument().getStatus()).isEqualTo(DocumentStatus.PENDING);
  }

  @Test
  void followsTheHappyPathPendingToProcessingToExtracted() {
    Document document = newDocument();

    document.transitionTo(DocumentStatus.PROCESSING);
    document.markExtracted("{\"currency\":\"EUR\"}");

    assertThat(document.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
    assertThat(document.getProposal()).isEqualTo("{\"currency\":\"EUR\"}");
    assertThat(document.getFailureReason()).isNull();
  }

  @Test
  void needsReviewKeepsTheProposalAndTheReason() {
    Document document = newDocument();
    document.transitionTo(DocumentStatus.PROCESSING);

    document.markNeedsReview("{\"currency\":\"XXX\"}", "Unknown currency");

    assertThat(document.getStatus()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
    assertThat(document.getProposal()).isEqualTo("{\"currency\":\"XXX\"}");
    assertThat(document.getFailureReason()).isEqualTo("Unknown currency");
  }

  @Test
  void extractedCannotGoBackToProcessing() {
    Document document = newDocument();
    document.transitionTo(DocumentStatus.PROCESSING);
    document.markExtracted("{}");

    assertThatThrownBy(() -> document.transitionTo(DocumentStatus.PROCESSING))
        .isInstanceOf(IllegalDocumentTransitionException.class);
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
  }

  @ParameterizedTest
  @EnumSource(DocumentStatus.class)
  void noTransitionOutOfFailedIsAllowed(DocumentStatus target) {
    Document document = newDocument();
    document.transitionTo(DocumentStatus.PROCESSING);
    document.markFailed("ai unreachable");

    assertThatThrownBy(() -> document.transitionTo(target))
        .isInstanceOf(IllegalDocumentTransitionException.class);
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
  }

  @Test
  void pendingCannotJumpStraightToExtracted() {
    Document document = newDocument();

    assertThatThrownBy(() -> document.markExtracted("{}"))
        .isInstanceOf(IllegalDocumentTransitionException.class);
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(document.getProposal()).isNull();
  }

  @Test
  void terminalStatusesAreTerminalAndTheOthersAreNot() {
    assertThat(DocumentStatus.EXTRACTED.isTerminal()).isTrue();
    assertThat(DocumentStatus.NEEDS_REVIEW.isTerminal()).isTrue();
    assertThat(DocumentStatus.FAILED.isTerminal()).isTrue();
    assertThat(DocumentStatus.PENDING.isTerminal()).isFalse();
    assertThat(DocumentStatus.PROCESSING.isTerminal()).isFalse();
  }

  @Test
  void aNullTargetIsNeverALegalTransition() {
    assertThat(DocumentStatus.PENDING.canTransitionTo(null)).isFalse();
  }

  private Document newDocument() {
    return new Document(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "invoice.pdf",
        "application/pdf",
        1024L,
        UUID.randomUUID().toString(),
        "abc123");
  }
}
