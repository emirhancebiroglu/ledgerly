package com.ledgerly.api.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/policies} — upload, chunk+embed (stubbed `ai`), and persist as
 * {@code policy_chunk} rows, org-scoped.
 */
@AutoConfigureMockMvc
@Import(PolicyUploadIT.AcceptingEmbeddingConfig.class)
class PolicyUploadIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aValidPdfIsAcceptedChunkedAndEmbedded() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult result =
        upload(token, "policy.pdf", REAL_PDF)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("EMBEDDED"))
            .andExpect(jsonPath("$.filename").value("policy.pdf"))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn();

    String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    Long chunkCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM policy_chunk WHERE policy_document_id = ?::uuid",
            Long.class,
            id);
    assertThat(chunkCount).isEqualTo(2L);
  }

  @Test
  void aNonPdfUploadIsRejectedWith415AndWritesNoChunks() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);

    upload(token, "policy.txt", "not a pdf".getBytes(StandardCharsets.UTF_8))
        .andExpect(status().isUnsupportedMediaType());

    Long chunkCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM policy_chunk WHERE organization_id = ?", Long.class, orgId);
    assertThat(chunkCount).isZero();
  }

  @Test
  void anEmptyUploadIsRejected() throws Exception {
    String token = registerAndGetAccessToken();

    upload(token, "empty.pdf", new byte[0]).andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void anotherOrganizationsPolicyDocumentIsNotReadable() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();

    MvcResult uploaded =
        upload(tokenA, "policy.pdf", REAL_PDF).andExpect(status().isCreated()).andReturn();
    String id = objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();

    mockMvc
        .perform(get("/api/v1/policies/" + id).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/policies/" + id).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  @Test
  void unauthenticatedReturns401() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/policies").file(new MockMultipartFile("file", "p.pdf", null, REAL_PDF)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anAiFailureLeavesTheDocumentFailedNotStrandedAtPending(
      @Autowired StubPolicyEmbeddingClient stubClient) throws Exception {
    stubClient.failWith(() -> new PolicyEmbeddingUnavailableException("simulated ai outage", null));
    try {
      String token = registerAndGetAccessToken();

      MvcResult result =
          upload(token, "policy.pdf", REAL_PDF)
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("FAILED"))
              .andReturn();

      String id =
          objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM policy_document WHERE id = ?::uuid", String.class, id);
      String failureReason =
          jdbcTemplate.queryForObject(
              "SELECT failure_reason FROM policy_document WHERE id = ?::uuid", String.class, id);
      assertThat(status).isEqualTo("FAILED");
      assertThat(failureReason).isNotNull();
    } finally {
      stubClient.reset();
    }
  }

  private org.springframework.test.web.servlet.ResultActions upload(
      String token, String filename, byte[] content) throws Exception {
    return mockMvc.perform(
        multipart("/api/v1/policies")
            .file(new MockMultipartFile("file", filename, null, content))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "key-" + System.nanoTime()));
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "policy-user-" + System.nanoTime() + "@example.com";
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

  /**
   * Stands in for `ai`. Defaults to two fixed chunks with tiny deterministic embeddings;
   * {@link #failWith} lets a test provoke the failure path deterministically.
   */
  static class StubPolicyEmbeddingClient implements PolicyEmbeddingClient {

    private java.util.function.Supplier<RuntimeException> failure;

    void failWith(java.util.function.Supplier<RuntimeException> failure) {
      this.failure = failure;
    }

    void reset() {
      this.failure = null;
    }

    @Override
    public String embedPolicy(UUID policyDocumentId, byte[] content, String contentType) {
      if (failure != null) {
        throw failure.get();
      }
      return """
          {"policy_document_id":"%s","model":"fake-embedding-v1","embedding_dimensions":4,
           "chunks":[
             {"chunk_index":0,"chunk_text":"Travel over 500 EUR needs approval.",
              "embedding":[0.1,0.2,0.3,0.4]},
             {"chunk_index":1,"chunk_text":"Meals reimbursed up to 50 EUR per day.",
              "embedding":[0.4,0.3,0.2,0.1]}
           ]}
          """
          .formatted(policyDocumentId);
    }
  }

  @TestConfiguration
  static class AcceptingEmbeddingConfig {

    @Bean
    @Primary
    StubPolicyEmbeddingClient stubPolicyEmbeddingClient() {
      return new StubPolicyEmbeddingClient();
    }
  }
}
