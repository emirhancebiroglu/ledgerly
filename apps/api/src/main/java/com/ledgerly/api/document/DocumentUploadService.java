package com.ledgerly.api.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.storage.BlobRollbackCleanup;
import com.ledgerly.api.storage.StorageClient;
import com.ledgerly.api.ratelimit.UploadRateLimiter;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts an upload: identifies it by its bytes, stores the blob, and records the document.
 *
 * <p>The stored blob is written before the row is committed. A blob with no row is inert — it is
 * unreachable, since the only handle to it is the key on that row — whereas a row pointing at a
 * blob that was never written would be a document that can never be read.
 */
@Service
public class DocumentUploadService {

  private final DocumentRepository documentRepository;
  private final StorageClient storageClient;
  private final BlobRollbackCleanup blobRollbackCleanup;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final UploadRateLimiter uploadRateLimiter;
  private final long maxBytes;

  public DocumentUploadService(
      DocumentRepository documentRepository,
      StorageClient storageClient,
      BlobRollbackCleanup blobRollbackCleanup,
      AuditService auditService,
      ObjectMapper objectMapper,
      UploadRateLimiter uploadRateLimiter,
      @Value("${ledgerly.document.max-bytes:10485760}") long maxBytes) {
    this.documentRepository = documentRepository;
    this.storageClient = storageClient;
    this.blobRollbackCleanup = blobRollbackCleanup;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.uploadRateLimiter = uploadRateLimiter;
    this.maxBytes = maxBytes;
  }

  @Transactional
  public Document upload(byte[] content, String filename, AuthenticatedPrincipal principal) {
    if (content == null || content.length == 0) {
      throw new UnsupportedDocumentTypeException("Uploaded document is empty");
    }
    if (content.length > maxBytes) {
      throw new DocumentTooLargeException(
          "Document exceeds the maximum accepted size of " + maxBytes + " bytes");
    }

    // The declared Content-Type and the filename extension are both caller-controlled. Only the
    // bytes decide what this is.
    DetectedContentType detected =
        DetectedContentType.detect(content)
            .orElseThrow(
                () ->
                    new UnsupportedDocumentTypeException(
                    "Unsupported document type; expected PDF, JPEG or PNG"));

    // Validation deliberately precedes this check: malformed input must not spend quota.
    uploadRateLimiter.checkDocumentUpload(principal.organizationId());

    String storageKey = storageClient.store(content);
    blobRollbackCleanup.registerOnRollback(storageKey);

    Document document =
        documentRepository.save(
            new Document(
                principal.organizationId(),
                principal.userId(),
                FilenameSanitizer.sanitize(filename, "document"),
                detected.mediaType(),
                content.length,
                storageKey,
                ContentHasher.sha256Hex(content)));
    documentRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "UPLOAD",
        "document",
        document.getId(),
        null,
        auditPayload(document),
        CorrelationIds.current());

    return document;
  }

  @Transactional(readOnly = true)
  public Document findForOrganization(UUID documentId, AuthenticatedPrincipal principal) {
    return documentRepository
        .findByIdAndOrganizationId(documentId, principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
  }

  /** Audit payload deliberately excludes the storage key and the document's contents. */
  private String auditPayload(Document document) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "filename", document.getFilename(),
              "contentType", document.getContentType(),
              "sizeBytes", document.getSizeBytes(),
              "contentHash", document.getContentHash(),
              "status", document.getStatus().name()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize document for audit trail", e);
    }
  }
}
