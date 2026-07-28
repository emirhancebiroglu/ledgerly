package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "ledgerly.rate-limit.document-upload.max-requests=2",
      "ledgerly.rate-limit.policy-upload.max-requests=1",
      "ledgerly.rate-limit.document-upload.window-seconds=60",
      "ledgerly.rate-limit.policy-upload.window-seconds=60"
    })
class UploadRateLimitIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";
  private static final byte[] PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;

  @Test
  void document_quota_is_org_scoped_and_rejection_writes_no_third_document() throws Exception {
    String firstOrgToken = register();
    String secondOrgToken = register();

    upload("/api/v1/documents", firstOrgToken).andExpect(status().isCreated());
    upload("/api/v1/documents", firstOrgToken).andExpect(status().isCreated());
    upload("/api/v1/documents", firstOrgToken)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));

    assertThat(countDocuments(organizationIdOf(firstOrgToken))).isEqualTo(2);
    upload("/api/v1/documents", secondOrgToken).andExpect(status().isCreated());
  }

  @Test
  void invalid_upload_does_not_consume_document_quota() throws Exception {
    String token = register();
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "invalid.txt", null, "nope".getBytes()))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "invalid-" + System.nanoTime()))
        .andExpect(status().isUnsupportedMediaType());

    upload("/api/v1/documents", token).andExpect(status().isCreated());
    upload("/api/v1/documents", token).andExpect(status().isCreated());
    upload("/api/v1/documents", token).andExpect(status().isTooManyRequests());
  }

  @Test
  void unauthenticated_upload_does_not_consume_document_quota() throws Exception {
    String token = register();
    mockMvc
        .perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "x.pdf", null, PDF)))
        .andExpect(status().isUnauthorized());

    upload("/api/v1/documents", token).andExpect(status().isCreated());
    upload("/api/v1/documents", token).andExpect(status().isCreated());
    upload("/api/v1/documents", token).andExpect(status().isTooManyRequests());
  }

  @Test
  void policy_quota_is_separate_from_document_quota() throws Exception {
    String token = register();

    upload("/api/v1/policies", token).andExpect(status().isCreated());
    upload("/api/v1/policies", token).andExpect(status().isTooManyRequests());
    upload("/api/v1/documents", token).andExpect(status().isCreated());
  }

  private org.springframework.test.web.servlet.ResultActions upload(String path, String token)
      throws Exception {
    return mockMvc.perform(
        multipart(path)
            .file(new MockMultipartFile("file", "document.pdf", null, PDF))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "upload-" + System.nanoTime()));
  }

  private String register() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(),
                                "rate-limit-" + System.nanoTime() + "@example.com",
                                "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
  }

  private UUID organizationIdOf(String token) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  private long countDocuments(UUID organizationId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM document WHERE organization_id = ?")) {
      statement.setObject(1, organizationId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }
}
