package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
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
 * {@code GET /api/v1/documents/{id}/events}: durable activity replay with Redis live delivery.
 */
@AutoConfigureMockMvc
class DocumentEventControllerIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DocumentStatusTransitions documentStatusTransitions;

  @Test
  void emitsActivityTransitionsUntilTerminalThenCloses() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID documentId = insertPendingDocument(org, token);

    MvcResult subscribeResult =
        mockMvc
            .perform(get("/api/v1/documents/" + documentId + "/events").header("Authorization", "Bearer " + token))
            .andExpect(request -> assertThat(request.getRequest().isAsyncStarted()).isTrue())
            .andReturn();

    documentStatusTransitions.markProcessing(documentId, org);
    documentStatusTransitions.recordFailure(documentId, org, "extraction failed");

    // Blocks until the emitter completes or this timeout elapses -- covers the real Redis relay
    // + SSE delivery round trip rather than assuming a fixed delay is enough. The SseEmitter
    // writes directly into this same MockHttpServletResponse as events are sent, so the body is
    // already fully populated once the emitter completes -- no async redispatch needed to read
    // it (and MockMvc's async redispatch replays the whole security filter chain against an
    // already-committed streamed response, which is a MockMvc/Spring Security test-harness
    // interaction, not something a real container does for an already-flushed SSE body).
    subscribeResult.getAsyncResult(10_000);
    String body = subscribeResult.getResponse().getContentAsString();

    assertThat(body).contains("EXTRACTING");
    assertThat(body).contains("FAILED");
    assertThat(body).contains("extraction failed");
  }

  @Test
  void replaysOnlyEventsAfterLastEventId() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID documentId = insertPendingDocument(org, token);
    documentStatusTransitions.markProcessing(documentId, org);
    long extractingId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM document_activity WHERE document_id = ? AND stage = 'EXTRACTING'",
            Long.class,
            documentId);
    documentStatusTransitions.recordFailure(documentId, org, "extraction failed");

    MvcResult replay =
        mockMvc
            .perform(
                get("/api/v1/documents/" + documentId + "/events")
                    .header("Authorization", "Bearer " + token)
                    .header("Last-Event-ID", extractingId))
            .andReturn();
    replay.getAsyncResult(10_000);
    String body = replay.getResponse().getContentAsString();

    assertThat(body).contains("FAILED");
    assertThat(body).doesNotContain("EXTRACTING");
  }

  @Test
  void anotherOrganizationsDocumentReturns404() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID documentId = insertPendingDocument(orgA, tokenA);

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/events").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  @Test
  void noTokenReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/documents/" + UUID.randomUUID() + "/events"))
        .andExpect(status().isUnauthorized());
  }

  private UUID insertPendingDocument(UUID orgId, String token) {
    UUID userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM app_user WHERE organization_id = ?", UUID.class, orgId);
    UUID documentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
            + "size_bytes, storage_key, content_hash, status) "
            + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 100, ?, 'hash', 'PENDING')",
        documentId,
        orgId,
        userId,
        UUID.randomUUID().toString());
    return documentId;
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "document-event-user-" + System.nanoTime() + "@example.com";
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
