package com.ledgerly.api.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.document.ContentHasher;
import com.ledgerly.api.document.DocumentTooLargeException;
import com.ledgerly.api.document.FilenameSanitizer;
import com.ledgerly.api.document.UnsupportedDocumentTypeException;
import com.ledgerly.api.storage.BlobRollbackCleanup;
import com.ledgerly.api.storage.StorageClient;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional writes for {@link PolicyUploadService}.
 *
 * <p>These live in their own bean deliberately — a {@code @Transactional} method invoked via
 * {@code this} from inside the same bean bypasses the Spring proxy entirely and silently runs with
 * no transaction at all. {@link PolicyUploadService} previously called these methods on itself,
 * which meant a failed `ai` call left the row stranded at {@code PENDING} with the failure reason
 * lost, rather than reaching {@code FAILED} — the same trap {@link
 * com.ledgerly.api.document.DocumentStatusTransitions} and {@code ExpensePostingTransactions}
 * already document elsewhere in this codebase. {@link PolicyUploadService} now injects this type
 * instead.
 */
@Component
public class PolicyUploadTransactions {

  private final PolicyDocumentRepository policyDocumentRepository;
  private final PolicyChunkRepository policyChunkRepository;
  private final StorageClient storageClient;
  private final BlobRollbackCleanup blobRollbackCleanup;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final long maxBytes;

  public PolicyUploadTransactions(
      PolicyDocumentRepository policyDocumentRepository,
      PolicyChunkRepository policyChunkRepository,
      StorageClient storageClient,
      BlobRollbackCleanup blobRollbackCleanup,
      AuditService auditService,
      ObjectMapper objectMapper,
      @Value("${ledgerly.document.max-bytes:10485760}") long maxBytes) {
    this.policyDocumentRepository = policyDocumentRepository;
    this.policyChunkRepository = policyChunkRepository;
    this.storageClient = storageClient;
    this.blobRollbackCleanup = blobRollbackCleanup;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.maxBytes = maxBytes;
  }

  @Transactional
  public PolicyDocument createPendingDocument(
      byte[] content, String filename, AuthenticatedPrincipal principal, boolean isPdf) {
    if (content == null || content.length == 0) {
      throw new UnsupportedDocumentTypeException("Uploaded policy document is empty");
    }
    if (content.length > maxBytes) {
      throw new DocumentTooLargeException(
          "Policy document exceeds the maximum accepted size of " + maxBytes + " bytes");
    }
    if (!isPdf) {
      throw new UnsupportedDocumentTypeException("Policy documents must be PDF");
    }

    String storageKey = storageClient.store(content);
    blobRollbackCleanup.registerOnRollback(storageKey);

    PolicyDocument document =
        policyDocumentRepository.save(
            new PolicyDocument(
                principal.organizationId(),
                principal.userId(),
                FilenameSanitizer.sanitize(filename, "policy"),
                storageKey,
                ContentHasher.sha256Hex(content)));
    policyDocumentRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "UPLOAD",
        "policy_document",
        document.getId(),
        null,
        auditPayload(document),
        CorrelationIds.current());

    return document;
  }

  @Transactional
  public void markProcessing(UUID policyDocumentId, UUID organizationId) {
    PolicyDocument document = findForOrganization(policyDocumentId, organizationId);
    document.transitionTo(PolicyDocumentStatus.PROCESSING);
  }

  @Transactional
  public PolicyDocument recordFailure(UUID policyDocumentId, UUID organizationId, String reason) {
    PolicyDocument document = findForOrganization(policyDocumentId, organizationId);
    document.markFailed(reason);
    return document;
  }

  @Transactional
  public PolicyDocument recordEmbedded(
      UUID policyDocumentId, AuthenticatedPrincipal principal, EmbedPolicyResponse response) {
    PolicyDocument document = findForOrganization(policyDocumentId, principal.organizationId());

    List<PolicyChunk> chunks =
        response.chunks().stream()
            .map(
                chunk ->
                    new PolicyChunk(
                        principal.organizationId(),
                        document.getId(),
                        chunk.chunkIndex(),
                        chunk.chunkText(),
                        toFloatArray(chunk.embedding())))
            .toList();
    policyChunkRepository.saveAll(chunks);

    document.markEmbedded();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "EMBED",
        "policy_document",
        document.getId(),
        null,
        embeddedAuditPayload(document, chunks.size(), response.model()),
        CorrelationIds.current());

    return document;
  }

  @Transactional(readOnly = true)
  public PolicyDocument findForOrganization(UUID policyDocumentId, AuthenticatedPrincipal principal) {
    return findForOrganization(policyDocumentId, principal.organizationId());
  }

  private PolicyDocument findForOrganization(UUID policyDocumentId, UUID organizationId) {
    return policyDocumentRepository
        .findByIdAndOrganizationId(policyDocumentId, organizationId)
        .orElseThrow(
            () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));
  }

  private float[] toFloatArray(List<Double> values) {
    float[] result = new float[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i).floatValue();
    }
    return result;
  }

  private String auditPayload(PolicyDocument document) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "filename", document.getFilename(),
              "contentHash", document.getContentHash(),
              "status", document.getStatus().name()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize policy document for audit trail", e);
    }
  }

  private String embeddedAuditPayload(PolicyDocument document, int chunkCount, String model) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "filename", document.getFilename(),
              "status", document.getStatus().name(),
              "chunkCount", chunkCount,
              "embeddingModel", model));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize policy document for audit trail", e);
    }
  }
}
