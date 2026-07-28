package com.ledgerly.api.document;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.storage.StorageClient;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentController {

  private final DocumentUploadService documentUploadService;
  private final StorageClient storageClient;

  public DocumentController(
      DocumentUploadService documentUploadService,
      StorageClient storageClient) {
    this.documentUploadService = documentUploadService;
    this.storageClient = storageClient;
  }

  /**
   * Uploads a document and returns immediately with durable {@code PENDING} work. The scheduler
   * claims it later, so an unavailable `ai` service never makes an upload request fail. A client polls
   * {@code GET /api/v1/documents/{id}} for the terminal status.
   */
  @PostMapping("/api/v1/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentResponse upload(
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedPrincipal principal)
      throws IOException {
    Document uploaded =
        documentUploadService.upload(file.getBytes(), file.getOriginalFilename(), principal);
    return DocumentResponse.from(uploaded);
  }

  @GetMapping("/api/v1/documents/{id}")
  public DocumentResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return DocumentResponse.from(documentUploadService.findForOrganization(id, principal));
  }

  /**
   * The bytes behind the expense-detail document viewer. {@code attachment}, never {@code
   * inline}: a document is arbitrary user-uploaded content (M5.1 T5's magic-byte check is
   * prefix-only, so a polyglot file can carry a browser-renderable payload under an accepted
   * content type) — forcing a download instead of inline rendering is what keeps that content
   * from executing in this origin's context. {@code X-Content-Type-Options: nosniff} is not set
   * here; it is already global via {@code SecurityConfig}.
   */
  @GetMapping("/api/v1/documents/{id}/content")
  public ResponseEntity<byte[]> content(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    Document document = documentUploadService.findForOrganization(id, principal);
    byte[] bytes = storageClient.read(document.getStorageKey());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(document.getContentType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(document.getFilename()).build().toString())
        .body(bytes);
  }
}
