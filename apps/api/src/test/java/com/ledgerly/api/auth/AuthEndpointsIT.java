package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    assertThat(unknownEmailResult.getResponse().getContentAsString())
        .isEqualTo(wrongPasswordResult.getResponse().getContentAsString());
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
                                "org-" + System.nanoTime(), email, password))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private SecretKey testKey() {
    return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
  }
}
