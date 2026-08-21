package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerly.api.audit.AuditLogRepository;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A rolled-back upload transaction must not leave an orphaned blob on disk — the M5.1 T4 follow-up
 * from M4's object-storage ADR. Runs in its own Spring context (the {@code AuditService} override
 * below is deliberately not shared with {@link DocumentUploadIT}) so the failure injection cannot
 * bleed into sibling tests.
 */
@AutoConfigureMockMvc
@Import({DocumentUploadRollbackIT.AcceptingExtractionConfig.class, DocumentUploadRollbackIT.FailingAuditConfig.class})
class DocumentUploadRollbackIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  private static final byte[] REAL_PDF = TestPdfFactory.validPdf();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentRepository documentRepository;

  @Value("${ledgerly.storage.root}")
  private Path storageRoot;

  @Test
  void aRolledBackUploadLeavesNoRowAndNoBlob() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    long blobsBefore = countBlobFiles();

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", FailingAuditConfig.TRIGGER_FILENAME, null, REAL_PDF))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().is5xxServerError());

    assertThat(documentRepository.countByOrganizationId(orgId)).isZero();
    assertThat(countBlobFiles()).isEqualTo(blobsBefore);
  }

  @Test
  void aCommittedUploadLeavesExactlyOneReadableBlob() throws Exception {
    String token = registerAndGetAccessToken();
    long blobsBefore = countBlobFiles();

    MvcResult result =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(new MockMultipartFile("file", "ok.pdf", null, REAL_PDF))
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "commit-key-" + System.nanoTime()))
            .andExpect(status().isCreated())
            .andReturn();

    UUID documentId =
        UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    Document stored = documentRepository.findById(documentId).orElseThrow();

    assertThat(countBlobFiles()).isEqualTo(blobsBefore + 1);
    assertThat(Files.readAllBytes(blobPath(stored.getStorageKey()))).isEqualTo(REAL_PDF);
  }

  private Path blobPath(String storageKey) throws Exception {
    try (var walk = Files.walk(storageRoot)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().equals(storageKey))
          .findFirst()
          .orElseThrow();
    }
  }

  private long countBlobFiles() throws Exception {
    if (!Files.isDirectory(storageRoot)) {
      return 0;
    }
    try (var walk = Files.walk(storageRoot)) {
      return walk.filter(Files::isRegularFile).count();
    }
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "rollback-user-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private UUID organizationIdOf(String accessToken) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  @TestConfiguration
  static class AcceptingExtractionConfig {

    @Bean
    @Primary
    ExtractionClient acceptingExtractionClient() {
      return (documentId, content, contentType, filename) ->
          """
          {"document_id":"%s","vendor":"Contoso","currency":"EUR","total_minor":12100,
           "tax_minor":2100,"document_date":"%s",
           "lines":[{"description":"item a","quantity":1000,"amount_minor":4000},
                    {"description":"item b","quantity":1000,"amount_minor":6000}],
           "confidence":{"vendor":0.9,"currency":0.99,"total_minor":0.95,"tax_minor":0.9,
                         "document_date":0.93},
           "model":"fake-llm-v1","warnings":[]}
          """
              .formatted(documentId, LocalDate.now().minusDays(3));
    }
  }

  @TestConfiguration
  static class FailingAuditConfig {

    /** Only an upload carrying this filename triggers the forced audit failure below. */
    static final String TRIGGER_FILENAME = "force-audit-failure.pdf";

    @Bean
    @Primary
    AuditService failingAuditService(AuditLogRepository auditLogRepository) {
      return new AuditService(auditLogRepository) {
        @Override
        public void record(
            UUID organizationId,
            UUID actor,
            String action,
            String entityType,
            UUID entityId,
            String beforeJson,
            String afterJson,
            UUID correlationId) {
          if ("document".equals(entityType) && afterJson != null && afterJson.contains(TRIGGER_FILENAME)) {
            throw new RuntimeException("forced audit failure for rollback test");
          }
          super.record(
              organizationId, actor, action, entityType, entityId, beforeJson, afterJson, correlationId);
        }
      };
    }
  }
}
