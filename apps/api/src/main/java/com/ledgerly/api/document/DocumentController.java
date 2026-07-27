package com.ledgerly.api.document;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
  private final DocumentProcessingService documentProcessingService;

  public DocumentController(
      DocumentUploadService documentUploadService,
      DocumentProcessingService documentProcessingService) {
    this.documentUploadService = documentUploadService;
    this.documentProcessingService = documentProcessingService;
  }

  /**
   * Uploads a document and processes it before responding.
   *
   * <p>Synchronous at M4: the stub answers instantly, so the caller sees a terminal status without
   * polling. Whether this moves off-thread is decided at M5 from observed latency (architecture
   * Q3) — the status lifecycle already models the asynchronous case.
   */
  @PostMapping("/api/v1/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentResponse upload(
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedPrincipal principal)
      throws IOException {
    Document uploaded =
        documentUploadService.upload(file.getBytes(), file.getOriginalFilename(), principal);
    return DocumentResponse.from(
        documentProcessingService.process(uploaded.getId(), principal.organizationId()));
  }

  @GetMapping("/api/v1/documents/{id}")
  public DocumentResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return DocumentResponse.from(documentUploadService.findForOrganization(id, principal));
  }
}
