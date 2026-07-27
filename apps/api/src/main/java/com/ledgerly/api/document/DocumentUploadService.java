package com.ledgerly.api.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import com.ledgerly.api.storage.StorageClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
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
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final long maxBytes;

  public DocumentUploadService(
      DocumentRepository documentRepository,
      StorageClient storageClient,
      AuditService auditService,
      ObjectMapper objectMapper,
      @Value("${ledgerly.document.max-bytes:10485760}") long maxBytes) {
    this.documentRepository = documentRepository;
    this.storageClient = storageClient;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
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

    String storageKey = storageClient.store(content);

    Document document =
        documentRepository.save(
            new Document(
                principal.organizationId(),
                principal.userId(),
                sanitizeFilename(filename),
                detected.mediaType(),
                content.length,
                storageKey,
                sha256Hex(content)));
    documentRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "UPLOAD",
        "document",
        document.getId(),
        null,
        auditPayload(document),
        correlationId());

    return document;
  }

  @Transactional(readOnly = true)
  public Document findForOrganization(UUID documentId, AuthenticatedPrincipal principal) {
    return documentRepository
        .findByIdAndOrganizationId(documentId, principal.organizationId())
        .orElseThrow(
            () -> new java.util.NoSuchElementException("Document not found: " + documentId));
  }

  /**
   * Keeps a display name only. The stored name is never used to build a path — the storage key is
   * the only handle — but a name carrying separators or control characters would still be a hazard
   * for any downstream consumer that renders or re-serves it.
   */
  private String sanitizeFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      return "document";
    }
    String withoutPath = filename.replaceAll(".*[/\\\\]", "");
    String cleaned = withoutPath.replaceAll("[\\p{Cntrl}]", "").trim();
    if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
      return "document";
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

  private UUID correlationId() {
    String current = CorrelationIdHolder.current();
    if (current == null) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(current);
    } catch (IllegalArgumentException notAUuid) {
      return UUID.nameUUIDFromBytes(current.getBytes(StandardCharsets.UTF_8));
    }
  }
}
