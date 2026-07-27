package com.ledgerly.api.policy;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.document.DetectedContentType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Accepts a policy document upload, stores the blob, asks `ai` to chunk and embed it, and persists
 * the resulting {@code policy_chunk} rows — synchronously, unlike document extraction (M5 made
 * that async because the whole document pipeline runs per-upload at high volume; policy uploads
 * are rare, operator-driven events where a client waiting a few seconds for a definitive answer is
 * the simpler contract).
 *
 * <p>Orchestration only — every actual write goes through {@link PolicyUploadTransactions}, a
 * separate bean called via injection rather than {@code this} (see that class's Javadoc for why:
 * self-invocation here previously left a failed upload stranded at {@code PENDING} instead of
 * reaching {@code FAILED}).
 */
@Service
public class PolicyUploadService {

  private final PolicyEmbeddingClient policyEmbeddingClient;
  private final EmbedPolicyResponseMapper responseMapper;
  private final PolicyUploadTransactions transactions;

  public PolicyUploadService(
      PolicyEmbeddingClient policyEmbeddingClient,
      EmbedPolicyResponseMapper responseMapper,
      PolicyUploadTransactions transactions) {
    this.policyEmbeddingClient = policyEmbeddingClient;
    this.responseMapper = responseMapper;
    this.transactions = transactions;
  }

  /**
   * Uploads, embeds and persists a policy document's chunks in one call.
   *
   * <p>Split into two transactions deliberately: the row-creation half commits before the `ai`
   * call so a slow embedding call never holds a database transaction open (same rationale as
   * {@link com.ledgerly.api.document.DocumentExtractionWorker}), and the outcome half commits the
   * chunks and the terminal status together so a document can never be left {@code EMBEDDED} with
   * zero chunks, or {@code PROCESSING} forever after a chunk-write failure.
   */
  public PolicyDocument upload(byte[] content, String filename, AuthenticatedPrincipal principal) {
    boolean isPdf =
        content != null
            && DetectedContentType.detect(content)
                .filter(candidate -> candidate == DetectedContentType.PDF)
                .isPresent();
    PolicyDocument document =
        transactions.createPendingDocument(content, filename, principal, isPdf);
    transactions.markProcessing(document.getId(), principal.organizationId());

    String rawResponse;
    try {
      rawResponse =
          policyEmbeddingClient.embedPolicy(document.getId(), content, "application/pdf");
    } catch (RuntimeException e) {
      return transactions.recordFailure(document.getId(), principal.organizationId(), e.getMessage());
    }

    EmbedPolicyResponse response;
    try {
      response = responseMapper.parse(rawResponse);
    } catch (MalformedEmbedPolicyResponseException e) {
      return transactions.recordFailure(document.getId(), principal.organizationId(), e.getMessage());
    }

    return transactions.recordEmbedded(document.getId(), principal, response);
  }

  public PolicyDocument findForOrganization(UUID policyDocumentId, AuthenticatedPrincipal principal) {
    return transactions.findForOrganization(policyDocumentId, principal);
  }
}
