package com.ledgerly.api.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/v1/policies} and {@code GET /api/v1/policies/{id}/chunks} — org-scoped reads
 * of what {@link PolicyUploadIT} writes. Reuses its stub `ai` client (two fixed chunks per
 * successful upload) so chunk counts are deterministic without a real embedding call.
 */
@AutoConfigureMockMvc
@Import(PolicyUploadIT.AcceptingEmbeddingConfig.class)
class PolicyListIT extends AbstractPostgresIT {

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void listReturnsOnlyTheCallersOrganizationNewestFirstWithChunkCounts() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();

    String firstId = uploadedId(tokenA, "first.pdf");
    String secondId = uploadedId(tokenA, "second.pdf");
    uploadedId(tokenB, "other-org.pdf");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/policies").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body).hasSize(2);
    assertThat(body.get(0).get("id").asText()).isEqualTo(secondId);
    assertThat(body.get(1).get("id").asText()).isEqualTo(firstId);
    assertThat(body.get(0).get("chunkCount").asLong()).isEqualTo(2L);
    assertThat(body.get(1).get("chunkCount").asLong()).isEqualTo(2L);
  }

  @Test
  void listReportsZeroChunksForAFailedDocumentRatherThanNullOrCrashing(
      @Autowired PolicyUploadIT.StubPolicyEmbeddingClient stubClient) throws Exception {
    stubClient.failWith(() -> new PolicyEmbeddingUnavailableException("simulated ai outage", null));
    String token = registerAndGetAccessToken();
    try {
      mockMvc
          .perform(
              multipart("/api/v1/policies")
                  .file(new MockMultipartFile("file", "failed.pdf", null, REAL_PDF))
                  .header("Authorization", "Bearer " + token)
                  .header("Idempotency-Key", "key-" + System.nanoTime()))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("FAILED"));
    } finally {
      stubClient.reset();
    }

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/policies").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("status").asText()).isEqualTo("FAILED");
    assertThat(body.get(0).get("chunkCount").asLong()).isZero();
  }

  @Test
  void listOnAnOrganizationWithNoPoliciesReturnsAnEmptyArrayNot404() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/policies").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void negativePageIs400() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(
            get("/api/v1/policies?page=-1").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nonPositiveSizeIs400() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/policies?size=0").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oversizedSizeIsClampedNotRejected() throws Exception {
    String token = registerAndGetAccessToken();
    uploadedId(token, "one.pdf");

    mockMvc
        .perform(
            get("/api/v1/policies?size=99999").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void listRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
  }

  @Test
  void chunksReturnIndexOrderedTextWithNoEmbeddingField() throws Exception {
    String token = registerAndGetAccessToken();
    String id = uploadedId(token, "policy.pdf");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/policies/" + id + "/chunks").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body).hasSize(2);
    assertThat(body.get(0).get("index").asInt()).isEqualTo(0);
    assertThat(body.get(1).get("index").asInt()).isEqualTo(1);
    assertThat(body.get(0).get("text").asText()).isEqualTo("Travel over 500 EUR needs approval.");
    assertThat(body.get(0).has("embedding")).isFalse();
    assertThat(body.toString()).doesNotContain("embedding");
  }

  @Test
  void chunksOnAFailedDocumentReturnAnEmptyArrayNot404(
      @Autowired PolicyUploadIT.StubPolicyEmbeddingClient stubClient) throws Exception {
    stubClient.failWith(() -> new PolicyEmbeddingUnavailableException("simulated ai outage", null));
    try {
      String token = registerAndGetAccessToken();
      MvcResult uploaded =
          mockMvc
              .perform(
                  multipart("/api/v1/policies")
                      .file(new MockMultipartFile("file", "policy.pdf", null, REAL_PDF))
                      .header("Authorization", "Bearer " + token)
                      .header("Idempotency-Key", "key-" + System.nanoTime()))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("FAILED"))
              .andReturn();
      String id = objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();

      mockMvc
          .perform(get("/api/v1/policies/" + id + "/chunks").header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$").isEmpty());
    } finally {
      stubClient.reset();
    }
  }

  @Test
  void chunksForAForeignOrganizationsDocumentAreNotFound() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    String id = uploadedId(tokenA, "policy.pdf");

    mockMvc
        .perform(get("/api/v1/policies/" + id + "/chunks").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  @Test
  void chunksForAnUnknownDocumentIdAreNotFound() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(
            get("/api/v1/policies/" + java.util.UUID.randomUUID() + "/chunks")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void chunksPaginationReturnsTheCorrectContiguousSlice() throws Exception {
    String token = registerAndGetAccessToken();
    String id = uploadedId(token, "policy.pdf");

    mockMvc
        .perform(
            get("/api/v1/policies/" + id + "/chunks?page=0&size=1")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].index").value(0));

    mockMvc
        .perform(
            get("/api/v1/policies/" + id + "/chunks?page=1&size=1")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].index").value(1));
  }

  private String uploadedId(String token, String filename) throws Exception {
    MvcResult uploaded =
        mockMvc
            .perform(
                multipart("/api/v1/policies")
                    .file(new MockMultipartFile("file", filename, null, REAL_PDF))
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "key-" + System.nanoTime()))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "policy-list-user-" + System.nanoTime() + "@example.com";
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
}
