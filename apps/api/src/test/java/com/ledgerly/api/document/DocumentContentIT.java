package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import com.ledgerly.api.storage.StorageClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/v1/documents/{id}/content}: M7a T5. The bytes behind the expense-detail
 * document viewer.
 */
@AutoConfigureMockMvc
class DocumentContentIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StorageClient storageClient;

  @Test
  void streamsTheStoredBlobWithTheRightContentTypeAndDisposition() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    byte[] content = "%PDF-1.7 fake pdf bytes".getBytes(StandardCharsets.UTF_8);
    String storageKey = storageClient.store(content);
    UUID documentId = insertDocument(org, storageKey, "invoice.pdf", "application/pdf");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/documents/" + documentId + "/content").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn();

    String disposition = result.getResponse().getHeader("Content-Disposition");
    assertThat(disposition).isEqualTo("attachment; filename=\"invoice.pdf\"");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
  }

  @Test
  void aFilenameContainingCrlfCannotInjectAResponseHeader() throws Exception {
    // FilenameSanitizer strips control characters (including CR/LF) at upload time --
    // ContentDisposition itself does not, so this endpoint's safety against response splitting
    // depends entirely on that upload-time step. This pins the two together: if a future change
    // ever weakens the sanitizer, this fails instead of silently reopening the injection.
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    byte[] content = "%PDF-1.7 fake pdf bytes".getBytes(StandardCharsets.UTF_8);
    String storageKey = storageClient.store(content);
    String sanitizedFilename =
        FilenameSanitizer.sanitize("evil\r\nX-Injected: yes.pdf", "invoice.pdf");
    UUID documentId = insertDocument(org, storageKey, sanitizedFilename, "application/pdf");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/documents/" + documentId + "/content").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    String disposition = result.getResponse().getHeader("Content-Disposition");
    assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
    assertThat(result.getResponse().getHeader("X-Injected")).isNull();
  }

  @Test
  void anotherOrganizationsDocumentReturns404WithoutTouchingStorage() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    String storageKey = storageClient.store("secret bytes".getBytes(StandardCharsets.UTF_8));
    UUID documentId = insertDocument(orgA, storageKey, "invoice.pdf", "application/pdf");

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  @Test
  void aDocumentWhoseBlobIsMissingReturns404NotA500() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    // A syntactically valid key that was never actually stored.
    UUID documentId = insertDocument(org, UUID.randomUUID().toString(), "invoice.pdf", "application/pdf");

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void noTokenReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/documents/" + UUID.randomUUID() + "/content"))
        .andExpect(status().isUnauthorized());
  }

  private UUID insertDocument(UUID orgId, String storageKey, String filename, String contentType) {
    UUID userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM app_user WHERE organization_id = ?", UUID.class, orgId);
    UUID documentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
            + "size_bytes, storage_key, content_hash, status) "
            + "VALUES (?, ?, ?, ?, ?, 100, ?, 'hash', 'EXTRACTED')",
        documentId,
        orgId,
        userId,
        filename,
        contentType,
        storageKey);
    return documentId;
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "document-content-user-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
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
}
