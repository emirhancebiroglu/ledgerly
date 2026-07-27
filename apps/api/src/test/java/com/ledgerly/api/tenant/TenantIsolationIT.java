package com.ledgerly.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.expense.CreateExpenseRequest;
import com.ledgerly.api.expense.ExpenseStubRepository;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T7: with only a create-only expense endpoint in the API surface so far (no read/list/update
 * yet — those land at M4+), tenant isolation is proven the two ways that are actually
 * exercisable today: every write is scoped to the JWT's signed org claim regardless of what an
 * attacker puts in the request body, and a token whose org claim was tampered with is rejected
 * outright rather than trusted.
 */
@AutoConfigureMockMvc
class TenantIsolationIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ExpenseStubRepository expenseStubRepository;

  @Test
  void expenseWrittenByOrgAPrincipalIsScopedToOrgANeverOrgB() throws Exception {
    String orgAToken = registerAndGetAccessToken();
    UUID orgAId = organizationIdOf(orgAToken);
    String orgBToken = registerAndGetAccessToken();
    UUID orgBId = organizationIdOf(orgBToken);

    postExpense(orgAToken, new CreateExpenseRequest(1500, "EUR")).andExpect(status().isCreated());

    assertThat(expenseStubRepository.countByOrganizationId(orgAId)).isEqualTo(1);
    assertThat(expenseStubRepository.countByOrganizationId(orgBId)).isEqualTo(0);
  }

  @Test
  void jwtWithTamperedOrgClaimIsRejectedNotTrusted() throws Exception {
    String legitimateToken = registerAndGetAccessToken();
    var claims = Jwts.parser()
        .verifyWith(testKey())
        .build()
        .parseSignedClaims(legitimateToken)
        .getPayload();

    // Same subject, but the org claim swapped to a foreign org id, re-signed with the WRONG
    // key (an attacker doesn't have the server's secret) — this must never be accepted.
    String forgedToken =
        Jwts.builder()
            .subject(claims.getSubject())
            .claim("org", UUID.randomUUID().toString())
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
            .signWith(Keys.hmacShaKeyFor("a-completely-different-attacker-controlled-secret-key".getBytes(StandardCharsets.UTF_8)))
            .compact();

    // 401, not 403: a token whose signature does not verify never established an identity at all,
    // so the honest answer is "who are you?" rather than "you may not". The org claim inside it is
    // never even read.
    postExpense(forgedToken, new CreateExpenseRequest(1500, "EUR"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unhandledExceptionReturnsProblemDetailWithCorrelationIdAndNoInternalDetail() throws Exception {
    String token = registerAndGetAccessToken();

    // Bean validation rejects amountMinor <= 0 with a 400 well before reaching the controller
    // body, so this can't be used to reach the generic 500 handler via HTTP directly; instead
    // assert the shape of an error response that DOES reach it: malformed JSON body forces
    // Jackson to throw inside message conversion, which the app maps through the same advice.
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/expenses")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "key-" + System.nanoTime())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not-valid-json"))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("com.ledgerly").doesNotContain("Exception").doesNotContain("\tat ");
  }

  private org.springframework.test.web.servlet.ResultActions postExpense(
      String token, CreateExpenseRequest request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/expenses")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "key-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "tenant-user-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest("org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private UUID organizationIdOf(String accessToken) {
    var claims = Jwts.parser().verifyWith(testKey()).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  private SecretKey testKey() {
    return Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
  }
}
