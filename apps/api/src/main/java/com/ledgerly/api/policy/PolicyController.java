package com.ledgerly.api.policy;

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
public class PolicyController {

  private final PolicyUploadService policyUploadService;

  public PolicyController(PolicyUploadService policyUploadService) {
    this.policyUploadService = policyUploadService;
  }

  /**
   * Uploads a policy PDF, chunks and embeds it via `ai`, and returns once the outcome is final —
   * {@code EMBEDDED} with its chunk count persisted, or {@code FAILED}. Unlike document upload,
   * this call is synchronous: policy uploads are rare, operator-driven, and a client waiting a few
   * seconds for a definitive answer is simpler than a poll loop for an endpoint hit this rarely.
   */
  @PostMapping("/api/v1/policies")
  @ResponseStatus(HttpStatus.CREATED)
  public PolicyDocumentResponse upload(
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedPrincipal principal)
      throws IOException {
    return PolicyDocumentResponse.from(
        policyUploadService.upload(file.getBytes(), file.getOriginalFilename(), principal));
  }

  @GetMapping("/api/v1/policies/{id}")
  public PolicyDocumentResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return PolicyDocumentResponse.from(policyUploadService.findForOrganization(id, principal));
  }
}
