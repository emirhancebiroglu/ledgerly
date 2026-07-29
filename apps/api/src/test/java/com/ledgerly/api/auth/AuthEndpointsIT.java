package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthEndpointsIT extends AbstractPostgresIT {

  private static final String TEST_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void registerThenLoginReturnsJwtWithOrgClaim() throws Exception {
    String email = "owner-" + System.nanoTime() + "@example.com";
    registerAndExpectCreated(email, "correct-horse-battery-staple");

    MvcResult loginResult =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(new LoginRequest(email, "correct-horse-battery-staple"))))
            .andExpect(status().isOk())
            .andReturn();

    AuthResponse response =
        objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);

    var claims =
        Jwts.parser().verifyWith(testKey()).build().parseSignedClaims(response.accessToken()).getPayload();
    assertThat(claims.get("org", String.class)).isNotBlank();
    assertThat(claims.getSubject()).isNotBlank();
  }

  @Test
  void loginWithWrongPasswordReturns401() throws Exception {
    String email = "user-" + System.nanoTime() + "@example.com";
    registerAndExpectCreated(email, "correct-horse-battery-staple");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(email, "wrong-password"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedMeReturnsOnlyTheTokenOwnersIdentityAndOrganization() throws Exception {
    String email = "profile-" + System.nanoTime() + "@example.com";
    AuthResponse response = registerAndExpectCreated(email, "correct-horse-battery-staple");

    mockMvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + response.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("Ledgerly Test User"))
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.organizationName").value(orgName(email)));

    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void registrationRejectsMissingNameAndShortPassword() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"Example\",\"email\":\"invalid@example.com\",\"password\":\"too-short\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void loginQuotaAllowsSuccessBeforeItRejectsFurtherAttempts() throws Exception {
    String email = "quota-" + System.nanoTime() + "@example.com";
    registerAndExpectCreated(email, "correct-horse-battery-staple");

    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(new LoginRequest(email, "wrong-password"))))
          .andExpect(status().isUnauthorized());
    }

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(email, "correct-horse-battery-staple"))))
        .andExpect(status().isOk());

    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(new LoginRequest(email, "wrong-password"))))
          .andExpect(status().isUnauthorized());
    }

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(email, "wrong-password"))))
        .andExpect(status().isTooManyRequests())
        .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank());
  }

  @Test
  void loginForNonexistentEmailReturnsSameShapeAs401AsWrongPassword() throws Exception {
    String realEmail = "user2-" + System.nanoTime() + "@example.com";
    registerAndExpectCreated(realEmail, "correct-horse-battery-staple");

    MvcResult wrongPasswordResult =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(new LoginRequest(realEmail, "wrong-password"))))
            .andExpect(status().isUnauthorized())
            .andReturn();

    MvcResult unknownEmailResult =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new LoginRequest(
                                "nobody-" + System.nanoTime() + "@example.com",
                                "wrong-password"))))
            .andExpect(status().isUnauthorized())
            .andReturn();

    // Bodies must agree on everything except correlationId, which is legitimately unique per
    // request (T6) — the security property under test is "same detail/status", not a
    // byte-identical response across two different requests.
    ObjectNode wrongPasswordBody =
        (ObjectNode) objectMapper.readTree(wrongPasswordResult.getResponse().getContentAsString());
    ObjectNode unknownEmailBody =
        (ObjectNode) objectMapper.readTree(unknownEmailResult.getResponse().getContentAsString());
    wrongPasswordBody.remove("correlationId");
    unknownEmailBody.remove("correlationId");

    assertThat(unknownEmailBody).isEqualTo(wrongPasswordBody);
  }

  @Test
  void refreshRotatesTokenAndRejectsReuseOfOldToken() throws Exception {
    String email = "refresher-" + System.nanoTime() + "@example.com";
    AuthResponse initial = registerAndExpectCreated(email, "correct-horse-battery-staple");

    MvcResult refreshResult =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(new RefreshRequest(initial.refreshToken()))))
            .andExpect(status().isOk())
            .andReturn();

    AuthResponse rotated =
        objectMapper.readValue(
            refreshResult.getResponse().getContentAsString(), AuthResponse.class);
    assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());

    // reusing the old (now-revoked) refresh token must be rejected
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new RefreshRequest(initial.refreshToken()))))
        .andExpect(status().isUnauthorized());

    // the new token still works
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new RefreshRequest(rotated.refreshToken()))))
        .andExpect(status().isOk());
  }

  private AuthResponse registerAndExpectCreated(String email, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new RegisterRequest(
                                "Ledgerly Test User", orgName(email), email, password))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private String orgName(String email) {
    return "org-" + email.substring(0, email.indexOf('@'));
  }

  private SecretKey testKey() {
    return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
  }
}
