package com.ledgerly.api.document;

import com.ledgerly.api.correlation.CorrelationIdHolder;
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
 * <p><strong>No ledger entry is written at M4/M5 — by design.</strong> A valid proposal reaches
 * {@code EXTRACTED} and stops there; posting arrives at M6, behind this same gate.
 */
@Component
public class DocumentExtractionWorker {

  private static final Logger log = LoggerFactory.getLogger(DocumentExtractionWorker.class);

  private final StorageClient storageClient;
  private final ExtractionClient extractionClient;
  private final ProposalMapper proposalMapper;
  private final ExtractionProposalValidator validator;
  private final DocumentStatusTransitions transitions;

  public DocumentExtractionWorker(
      StorageClient storageClient,
      ExtractionClient extractionClient,
      ProposalMapper proposalMapper,
      ExtractionProposalValidator validator,
      DocumentStatusTransitions transitions) {
    this.storageClient = storageClient;
    this.extractionClient = extractionClient;
    this.proposalMapper = proposalMapper;
    this.validator = validator;
    this.transitions = transitions;
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
    try {
      extractNow(documentId, organizationId);
    } finally {
      MDC.remove(CorrelationIdHolder.MDC_KEY);
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
    } catch (RuntimeException e) {
      // A timeout, a refused connection and a 5xx are the same event from here: no usable answer.
      log.warn("Extraction call failed for document {}: {}", documentId, e.toString());
      return transitions.recordFailure(
          documentId, organizationId, "Extraction service unavailable");
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
    return transitions.recordOutcome(
        documentId, organizationId, proposalMapper.toJson(proposal), validation);
  }
}
