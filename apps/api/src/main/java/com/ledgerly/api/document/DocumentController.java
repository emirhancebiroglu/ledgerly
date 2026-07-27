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

  public DocumentController(DocumentUploadService documentUploadService) {
    this.documentUploadService = documentUploadService;
  }

  @PostMapping("/api/v1/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentResponse upload(
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedPrincipal principal)
      throws IOException {
    return DocumentResponse.from(
        documentUploadService.upload(file.getBytes(), file.getOriginalFilename(), principal));
  }

  @GetMapping("/api/v1/documents/{id}")
  public DocumentResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return DocumentResponse.from(documentUploadService.findForOrganization(id, principal));
  }
}
