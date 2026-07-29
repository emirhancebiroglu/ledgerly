package com.ledgerly.api.document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional status writes for the extraction queue and worker.
 *
 * <p>These live in their own bean deliberately. A {@code @Transactional} method invoked via {@code
 * this} from inside the same bean bypasses the Spring proxy entirely and silently runs with no
 * transaction at all — so the caller injects this type instead (the same trap as the M3
 * idempotency work).
 *
 * <p>Publishes a {@link DocumentStatusChangedEvent} after every transition, for M7a T6's SSE
 * stream. {@link DocumentEventPublisher} relays it to Redis only once this method's transaction
 * commits ({@code @TransactionalEventListener}) — publishing inline here, before commit, would let
 * a subscriber see a status that a subsequent rollback then undoes.
 */
@Component
public class DocumentStatusTransitions {

  private final DocumentRepository documentRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final DocumentActivityService documentActivityService;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration retryInitialDelay;
  private final Duration retryMaxDelay;

  public DocumentStatusTransitions(
      DocumentRepository documentRepository,
      ApplicationEventPublisher eventPublisher,
      DocumentActivityService documentActivityService,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${ledgerly.document.queue.max-attempts:5}")
          int maxAttempts,
      @org.springframework.beans.factory.annotation.Value("${ledgerly.document.queue.retry-initial-seconds:5}")
          long retryInitialSeconds,
      @org.springframework.beans.factory.annotation.Value("${ledgerly.document.queue.retry-max-seconds:300}")
          long retryMaxSeconds) {
    this.documentRepository = documentRepository;
    this.eventPublisher = eventPublisher;
    this.documentActivityService = documentActivityService;
    this.clock = clock;
    if (maxAttempts < 1 || retryInitialSeconds < 1 || retryMaxSeconds < retryInitialSeconds) {
      throw new IllegalArgumentException("Document queue retry configuration is invalid");
    }
    this.maxAttempts = maxAttempts;
    this.retryInitialDelay = Duration.ofSeconds(retryInitialSeconds);
    this.retryMaxDelay = Duration.ofSeconds(retryMaxSeconds);
  }

  @Transactional
  public void markProcessing(UUID documentId, UUID organizationId) {
    Document document = load(documentId, organizationId);
    document.transitionTo(DocumentStatus.PROCESSING);
    activity(document, DocumentActivityStage.EXTRACTING, "Extracting document data");
    publish(document, null);
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
      publish(document, null);
    } else {
      // No ledger write happens on either branch at M4. When posting arrives at M6 it goes behind
      // this same condition, so a proposal that failed validation can never reach it.
      document.markExtractionNeedsReview(proposalJson, validation.summary());
      activity(document, DocumentActivityStage.EXTRACTION_NEEDS_REVIEW, validation.summary());
      publish(document, validation.summary());
    }
    return document;
  }

  @Transactional
  public Document recordFailure(UUID documentId, UUID organizationId, String reason) {
    Document document = load(documentId, organizationId);
    document.markFailed(reason);
    activity(document, DocumentActivityStage.FAILED, reason);
    publish(document, reason);
    return document;
  }

  /**
   * Records a transient dependency failure without losing the work item. The attempt count was
   * incremented by the atomic queue claim, so the final allowed failed call is terminal and every
   * earlier call is made due again only after bounded exponential backoff.
   */
  @Transactional
  public Document retryAfterTransientFailure(UUID documentId, UUID organizationId) {
    Document document = load(documentId, organizationId);
    if (document.getExtractionAttempts() >= maxAttempts) {
      document.markFailed("Extraction service unavailable after " + maxAttempts + " attempts");
      activity(document, DocumentActivityStage.FAILED, document.getFailureReason());
      publish(document, document.getFailureReason());
      return document;
    }

    Instant now = Instant.now(clock);
    Instant retryAt = now.plus(backoffForAttempt(document.getExtractionAttempts()));
    document.markPendingForRetry(retryAt, "Extraction service unavailable; retry scheduled", now);
    publish(document, document.getFailureReason());
    return document;
  }

  /** Returns true only for the one queue worker that won the conditional update. */
  @Transactional
  public boolean claimDueDocument(UUID documentId, UUID organizationId, Instant now) {
    boolean claimed = documentRepository.claimDueDocument(documentId, now, maxAttempts) > 0;
    if (claimed) {
      Document document = load(documentId, organizationId);
      activity(document, DocumentActivityStage.EXTRACTING, "Extracting document data");
      eventPublisher.publishEvent(
          new DocumentStatusChangedEvent(
              documentId, organizationId, DocumentStatus.PROCESSING, null));
    }
    return claimed;
  }

  /**
   * Gives a claimed row back to PostgreSQL when the bounded worker executor is full. This is not
   * an extraction attempt: decrementing the claim increment means load shedding cannot exhaust a
   * document's agent retry budget before a worker ever sees it.
   */
  @Transactional
  public boolean releaseClaimAfterDispatchRejection(
      UUID documentId, UUID organizationId, Instant now, Instant retryAt) {
    String reason = "Extraction queue is busy; dispatch retry scheduled";
    boolean released =
        documentRepository.releaseClaimAfterDispatchRejection(documentId, now, retryAt, reason) > 0;
    if (released) {
      eventPublisher.publishEvent(
          new DocumentStatusChangedEvent(documentId, organizationId, DocumentStatus.PENDING, reason));
    }
    return released;
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
   * <p>Publishes a {@link DocumentStatusChangedEvent} when this call actually wins the race: the
   * reaper's {@code stuck-after-seconds} default (5 min) is well inside the SSE emitter's timeout
   * (15 min), so a client's stream is very likely still open when a reap happens — the crash case
   * the reaper exists for is exactly when a user is most likely still watching the panel wondering
   * why the document never resolved.
   *
   * @param organizationId the caller ({@link DocumentReaper}) already has this from the candidate
   *     row it loaded to find this document; the bulk {@code @Modifying} query below only returns
   *     an affected-row count, not enough on its own to build the event.
   * @return true if this call actually reclaimed the row (see {@link
   *     DocumentRepository#reclaimStuckDocument} for why a race can legitimately return false)
   */
  @Transactional
  public boolean reclaimStuckDocument(
      UUID documentId, UUID organizationId, Instant cutoff, Instant now, String reason) {
    boolean reclaimed =
        documentRepository.reclaimStuckDocument(
                documentId, DocumentStatus.PROCESSING, cutoff, now, reason)
            > 0;
    if (reclaimed) {
      documentActivityService.record(
          documentId, organizationId, DocumentActivityStage.FAILED, reason);
      eventPublisher.publishEvent(
          new DocumentStatusChangedEvent(documentId, organizationId, DocumentStatus.FAILED, reason));
    }
    return reclaimed;
  }

  private void publish(Document document, String detail) {
    eventPublisher.publishEvent(
        new DocumentStatusChangedEvent(
            document.getId(), document.getOrganizationId(), document.getStatus(), detail));
  }

  private void activity(Document document, DocumentActivityStage stage, String detail) {
    documentActivityService.record(document.getId(), document.getOrganizationId(), stage, detail);
  }

  private Duration backoffForAttempt(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
    try {
      Duration backoff = retryInitialDelay.multipliedBy(multiplier);
      return backoff.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : backoff;
    } catch (ArithmeticException ignored) {
      return retryMaxDelay;
    }
  }
}
