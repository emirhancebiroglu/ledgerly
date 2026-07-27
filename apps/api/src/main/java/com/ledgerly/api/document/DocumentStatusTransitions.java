package com.ledgerly.api.document;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional status writes for {@link DocumentProcessingService}.
 *
 * <p>These live in their own bean deliberately. A {@code @Transactional} method invoked via {@code
 * this} from inside the same bean bypasses the Spring proxy entirely and silently runs with no
 * transaction at all — so the caller injects this type instead (the same trap as the M3
 * idempotency work).
 */
@Component
public class DocumentStatusTransitions {

  private final DocumentRepository documentRepository;

  public DocumentStatusTransitions(DocumentRepository documentRepository) {
    this.documentRepository = documentRepository;
  }

  @Transactional
  public void markProcessing(UUID documentId, UUID organizationId) {
    load(documentId, organizationId).transitionTo(DocumentStatus.PROCESSING);
  }

  /**
   * Writes the terminal status and the proposal in one transaction, so a document never describes
   * half an outcome.
   */
  @Transactional
  public Document recordOutcome(
      UUID documentId,
      UUID organizationId,
      String proposalJson,
      ProposalValidationResult validation) {
    Document document = load(documentId, organizationId);
    if (validation.isValid()) {
      document.markExtracted(proposalJson);
    } else {
      // No ledger write happens on either branch at M4. When posting arrives at M6 it goes behind
      // this same condition, so a proposal that failed validation can never reach it.
      document.markNeedsReview(proposalJson, validation.summary());
    }
    return document;
  }

  @Transactional
  public Document recordFailure(UUID documentId, UUID organizationId, String reason) {
    Document document = load(documentId, organizationId);
    document.markFailed(reason);
    return document;
  }

  @Transactional(readOnly = true)
  public Document load(UUID documentId, UUID organizationId) {
    return documentRepository
        .findByIdAndOrganizationId(documentId, organizationId)
        .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
  }

  /**
   * One document, one transaction — used by {@link DocumentReaper} so a crash partway through a
   * sweep leaves every already-reclaimed document reclaimed, not rolled back as a batch.
   *
   * @return true if this call actually reclaimed the row (see {@link
   *     DocumentRepository#reclaimStuckDocument} for why a race can legitimately return false)
   */
  @Transactional
  public boolean reclaimStuckDocument(UUID documentId, Instant cutoff, Instant now, String reason) {
    return documentRepository.reclaimStuckDocument(
            documentId, DocumentStatus.PROCESSING, cutoff, now, reason)
        > 0;
  }
}
