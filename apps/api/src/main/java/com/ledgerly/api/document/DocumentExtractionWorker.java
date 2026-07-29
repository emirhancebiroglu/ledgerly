package com.ledgerly.api.document;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import com.ledgerly.api.expense.ExpensePostingService;
import com.ledgerly.api.storage.StorageClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the actual extraction — fetch the bytes, ask `ai`, validate the answer, record the outcome
 * — off the request thread. A separate bean from {@link DocumentProcessingService} so
 * {@code @Async} goes through the Spring proxy instead of being silently skipped by a same-class
 * call (see that class's Javadoc).
 *
 * <p>The `ai` call deliberately happens outside any transaction: holding a database transaction
 * open across a network call to a service that might stall is how one slow dependency exhausts the
 * connection pool.
 *
 * <p>A proposal that fails validation reaches {@code NEEDS_REVIEW} and stops there — no
 * categorization call, no ledger entry (M4's trust-boundary gate). A proposal that passes reaches
 * {@code EXTRACTED} and is handed to {@link ExpensePostingService}, which either posts a balanced
 * ledger transaction or routes a low-confidence categorization to review (M6 T6/T7).
 */
@Component
public class DocumentExtractionWorker {

  private static final Logger log = LoggerFactory.getLogger(DocumentExtractionWorker.class);

  private final StorageClient storageClient;
  private final ExtractionClient extractionClient;
  private final ProposalMapper proposalMapper;
  private final ExtractionProposalValidator validator;
  private final DocumentStatusTransitions transitions;
  private final ExpensePostingService expensePostingService;
  private final DocumentActivityService documentActivityService;

  public DocumentExtractionWorker(
      StorageClient storageClient,
      ExtractionClient extractionClient,
      ProposalMapper proposalMapper,
      ExtractionProposalValidator validator,
      DocumentStatusTransitions transitions,
      ExpensePostingService expensePostingService,
      DocumentActivityService documentActivityService) {
    this.storageClient = storageClient;
    this.extractionClient = extractionClient;
    this.proposalMapper = proposalMapper;
    this.validator = validator;
    this.transitions = transitions;
    this.expensePostingService = expensePostingService;
    this.documentActivityService = documentActivityService;
  }

  /**
   * @param correlationId captured on the request thread by the caller before dispatch — MDC is
   *     thread-local, so this executor thread has none of its own to inherit.
   */
  @Async(com.ledgerly.api.AsyncConfig.DOCUMENT_PROCESSING_EXECUTOR)
  public void extractAsync(UUID documentId, UUID organizationId, String correlationId) {
    if (correlationId != null) {
      MDC.put(CorrelationIdHolder.MDC_KEY, correlationId);
    }
    MDC.put("service", "api");
    try {
      extractNow(documentId, organizationId);
    } finally {
      MDC.remove(CorrelationIdHolder.MDC_KEY);
      MDC.remove("service");
    }
  }

  private Document extractNow(UUID documentId, UUID organizationId) {
    Document document = transitions.load(documentId, organizationId);
    String rawProposal;
    try {
      byte[] content = storageClient.read(document.getStorageKey());
      rawProposal =
          extractionClient.extract(
              document.getId(), content, document.getContentType(), document.getFilename());
    } catch (ExtractionUnavailableException e) {
      // A timeout, a refused connection and a 5xx leave the durable work item PENDING for retry.
      log.warn("Extraction call failed documentId={} exceptionType={}", documentId, e.getClass().getSimpleName());
      return transitions.retryAfterTransientFailure(documentId, organizationId);
    } catch (ExtractionRequestRejectedException e) {
      log.warn("Extraction request rejected documentId={} exceptionType={}", documentId, e.getClass().getSimpleName());
      return transitions.recordFailure(documentId, organizationId, "Extraction service rejected the request");
    }

    ExtractionProposal proposal;
    try {
      proposal = proposalMapper.parse(rawProposal);
    } catch (MalformedProposalException e) {
      log.warn("Malformed proposal for document {}", documentId);
      return transitions.recordFailure(
          documentId, organizationId, "Extraction returned a malformed proposal");
    }

    if (!documentId.toString().equals(proposal.documentId())) {
      // A proposal about some other document would attach one document's data to another.
      log.warn("Proposal document id mismatch for document {}", documentId);
      return transitions.recordFailure(
          documentId, organizationId, "Extraction returned a mismatched proposal");
    }

    ProposalValidationResult validation = validator.validate(proposal);
    Document outcome =
        transitions.recordOutcome(
            documentId, organizationId, proposalMapper.toJson(proposal), validation);

    if (outcome.getStatus() == DocumentStatus.EXTRACTED) {
      categorizeAndPost(documentId, organizationId, document.getUploadedBy(), proposal);
    }
    return outcome;
  }

  /**
   * Categorization/posting failures are logged, not fatal to the pipeline run: the document has
   * already reached its correct terminal status ({@code EXTRACTED}) regardless of whether an
   * expense could be categorized from it. A future re-processing pass — not built at M6 — would be
   * the way to retry categorization for a document stuck without an expense.
   */
  private void categorizeAndPost(
      UUID documentId, UUID organizationId, UUID actor, ExtractionProposal proposal) {
    try {
      expensePostingService.categorizeAndPost(organizationId, documentId, actor, proposal);
    } catch (RuntimeException e) {
      log.warn("Categorization failed documentId={} exceptionType={}", documentId, e.getClass().getSimpleName());
      documentActivityService.record(
          documentId,
          organizationId,
          DocumentActivityStage.CATEGORIZATION_FAILED,
          "Categorization could not be completed");
    }
  }
}
