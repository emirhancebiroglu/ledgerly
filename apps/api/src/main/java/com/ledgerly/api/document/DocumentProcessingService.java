package com.ledgerly.api.document;

import com.ledgerly.api.storage.StorageClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives a document from {@code PENDING} to a terminal status: fetch the bytes, ask `ai`, validate
 * the answer, record the outcome.
 *
 * <p>M4 runs this synchronously. The stub answers instantly, so there is nothing to wait for, and
 * the async question (architecture Q3) is deferred to M5 where real latency can be observed. The
 * status lifecycle already models {@code PROCESSING}, so moving the call off-thread later is a
 * change here, not a schema change.
 *
 * <p><strong>No ledger entry is written at M4 — by design.</strong> A valid proposal reaches
 * {@code EXTRACTED} and stops there; posting arrives at M6, behind this same gate.
 */
@Service
public class DocumentProcessingService {

  private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

  private final StorageClient storageClient;
  private final ExtractionClient extractionClient;
  private final ProposalMapper proposalMapper;
  private final ExtractionProposalValidator validator;
  private final DocumentStatusTransitions transitions;

  public DocumentProcessingService(
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
   * Processes one document to a terminal status.
   *
   * <p>The `ai` call deliberately happens outside any transaction: holding a database transaction
   * open across a network call to a service that might stall is how one slow dependency exhausts
   * the connection pool.
   */
  public Document process(UUID documentId, UUID organizationId) {
    transitions.markProcessing(documentId, organizationId);

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
