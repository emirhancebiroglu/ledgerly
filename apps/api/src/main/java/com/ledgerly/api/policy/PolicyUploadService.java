package com.ledgerly.api.policy;

import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.document.DetectedContentType;
import com.ledgerly.api.document.DocumentTooLargeException;
import com.ledgerly.api.document.UnsupportedDocumentTypeException;
import com.ledgerly.api.storage.StorageClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Accepts a policy document upload, stores the blob, asks `ai` to chunk and embed it, and persists
 * the resulting {@code policy_chunk} rows — synchronously, unlike document extraction (M5 made
 * that async because the whole document pipeline runs per-upload at high volume; policy uploads
 * are rare, operator-driven events where a client waiting a few seconds for a definitive answer is
 * the simpler contract).
 */
@Service
public class PolicyUploadService {

  private final PolicyDocumentRepository policyDocumentRepository;
  private final PolicyChunkRepository policyChunkRepository;
  private final StorageClient storageClient;
  private final PolicyEmbeddingClient policyEmbeddingClient;
  private final EmbedPolicyResponseMapper responseMapper;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final long maxBytes;

  public PolicyUploadService(
      PolicyDocumentRepository policyDocumentRepository,
      PolicyChunkRepository policyChunkRepository,
      StorageClient storageClient,
      PolicyEmbeddingClient policyEmbeddingClient,
      EmbedPolicyResponseMapper responseMapper,
      AuditService auditService,
      ObjectMapper objectMapper,
      @Value("${ledgerly.document.max-bytes:10485760}") long maxBytes) {
    this.policyDocumentRepository = policyDocumentRepository;
    this.policyChunkRepository = policyChunkRepository;
    this.storageClient = storageClient;
    this.policyEmbeddingClient = policyEmbeddingClient;
    this.responseMapper = responseMapper;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.maxBytes = maxBytes;
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
    PolicyDocument document = createPendingDocument(content, filename, principal);
    markProcessing(document.getId(), principal.organizationId());

    String rawResponse;
    try {
      rawResponse =
          policyEmbeddingClient.embedPolicy(document.getId(), content, "application/pdf");
    } catch (RuntimeException e) {
      return recordFailure(document.getId(), principal.organizationId(), e.getMessage());
    }

    EmbedPolicyResponse response;
    try {
      response = responseMapper.parse(rawResponse);
    } catch (MalformedEmbedPolicyResponseException e) {
      return recordFailure(document.getId(), principal.organizationId(), e.getMessage());
    }

    return recordEmbedded(document.getId(), principal, response);
  }

  @Transactional
  public PolicyDocument createPendingDocument(
      byte[] content, String filename, AuthenticatedPrincipal principal) {
    if (content == null || content.length == 0) {
      throw new UnsupportedDocumentTypeException("Uploaded policy document is empty");
    }
    if (content.length > maxBytes) {
      throw new DocumentTooLargeException(
          "Policy document exceeds the maximum accepted size of " + maxBytes + " bytes");
    }
    boolean isPdf =
        DetectedContentType.detect(content)
            .filter(candidate -> candidate == DetectedContentType.PDF)
            .isPresent();
    if (!isPdf) {
      throw new UnsupportedDocumentTypeException("Policy documents must be PDF");
    }

    String storageKey = storageClient.store(content);
    registerBlobCleanupOnRollback(storageKey);

    PolicyDocument document =
        policyDocumentRepository.save(
            new PolicyDocument(
                principal.organizationId(),
                principal.userId(),
                sanitizeFilename(filename),
                storageKey,
                sha256Hex(content)));
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

  private String sanitizeFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      return "policy";
    }
    String withoutPath = filename.replaceAll(".*[/\\\\]", "");
    String cleaned = withoutPath.replaceAll("[\\p{Cntrl}]", "").trim();
    if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
      return "policy";
    }
    return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
  }

  private String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }

  private void registerBlobCleanupOnRollback(String storageKey) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
              storageClient.delete(storageKey);
            }
          }
        });
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
