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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The T4 demo command: a real document status change reaches a real {@code SseEmitter} through
 * {@code InMemoryDocumentEventBroker} with {@code ledgerly.document.event-broker=in-memory}.
 *
 * <p>{@code @ConditionalOnProperty} on both adapters already guarantees the in-memory code path
 * cannot construct a Redis type, but that is a static guarantee, not a demonstrated one — {@link
 * AbstractPostgresIT} always starts a reachable Redis Testcontainer, so a run against it alone
 * would not actually distinguish "never calls Redis" from "calls Redis, which happens to work."
 * {@link #unreachableRedis} points the connection at a port nothing listens on instead, the same
 * technique {@code RedisOutageIT} uses; this class passing proves the demo happens with a broken
 * Redis address in play, not merely a present one.
 *
 * <p>One scenario, not the full matrix {@link DocumentEventControllerIT} covers against Redis:
 * this class exists to prove the wiring reaches production code end to end, not to duplicate
 * {@link DocumentEventBrokerContract}'s behavioral guarantees a second time.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "ledgerly.document.event-broker=in-memory")
class DocumentEventControllerInMemoryBrokerIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void unreachableRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> 1);
  }

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DocumentStatusTransitions documentStatusTransitions;
  @Autowired private DocumentEventBroker broker;

  @Test
  void aStatusChangeReachesTheStreamWithNoRedisInvolved() throws Exception {
    assertThat(broker).isInstanceOf(InMemoryDocumentEventBroker.class);

    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID documentId = insertPendingDocument(org, token);

    MvcResult subscribeResult =
        mockMvc
            .perform(
                get("/api/v1/documents/" + documentId + "/events")
                    .header("Authorization", "Bearer " + token))
            .andExpect(request -> assertThat(request.getRequest().isAsyncStarted()).isTrue())
            .andReturn();

    documentStatusTransitions.markProcessing(documentId, org);
    documentStatusTransitions.recordFailure(documentId, org, "extraction failed");

    subscribeResult.getAsyncResult(10_000);
    String body = subscribeResult.getResponse().getContentAsString();

    assertThat(body).contains("EXTRACTING");
    assertThat(body).contains("FAILED");
    assertThat(body).contains("extraction failed");
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
    String email = "document-event-inmemory-user-" + System.nanoTime() + "@example.com";
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
