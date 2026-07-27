package com.ledgerly.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class IdempotencyFilterIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ExpenseStubRepository expenseStubRepository;
  @Autowired private DataSource dataSource;

  @Test
  void replayingSameKeyAndPayloadReturnsIdenticalResponseWithOneRowWritten() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    String payload = objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR"));
    String key = "key-" + System.nanoTime();

    MvcResult first = postExpense(token, key, payload).andExpect(status().isCreated()).andReturn();
    MvcResult second = postExpense(token, key, payload).andExpect(status().isCreated()).andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(expenseStubRepository.countByOrganizationId(orgId)).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentPayloadReturns409() throws Exception {
    String token = registerAndGetAccessToken();
    String key = "key-" + System.nanoTime();

    postExpense(token, key, objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR")))
        .andExpect(status().isCreated());

    postExpense(token, key, objectMapper.writeValueAsString(new CreateExpenseRequest(999, "EUR")))
        .andExpect(status().isConflict());
  }

  @Test
  void secondRequestWhileFirstIsInProgressReturns409() throws Exception {
    String token = registerAndGetAccessToken();
    String key = "key-" + System.nanoTime();
    UUID orgId = organizationIdOf(token);
    String payload = objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR"));

    insertInProgressRecord(orgId, key, "/api/v1/expenses");

    postExpense(token, key, payload).andExpect(status().isConflict());
  }

  @Test
  void concurrentRequestsWithSameFreshKeyProduceExactlyOneWriteAndAgreeingResponses()
      throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    String key = "key-" + System.nanoTime();
    String payload = objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR"));

    // Only one caller can ever win the DB-level claim on a fresh key. The loser's outcome
    // depends on how far the winner has gotten: 409 (winner still IN_PROGRESS) or a replayed
    // 201 with an identical body (winner already completed) are both correct — idempotency
    // guarantees no second effect, not a specific race outcome. What must always hold: exactly
    // one row written, and any two 201s carry the same body.
    CountDownLatch bothReady = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<MvcResult> results;
    try {
      Callable<MvcResult> race = () -> {
        bothReady.countDown();
        go.await(10, TimeUnit.SECONDS);
        return postExpense(token, key, payload).andReturn();
      };
      Future<MvcResult> first = executor.submit(race);
      Future<MvcResult> second = executor.submit(race);
      bothReady.await(10, TimeUnit.SECONDS);
      go.countDown();
      results = List.of(getUnchecked(first), getUnchecked(second));
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
    assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(201, 409));
    assertThat(statuses).contains(201);

    List<String> createdBodies =
        results.stream()
            .filter(r -> r.getResponse().getStatus() == 201)
            .map(r -> {
              try {
                return r.getResponse().getContentAsString();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
            .distinct()
            .toList();
    assertThat(createdBodies).hasSize(1);

    assertThat(expenseStubRepository.countByOrganizationId(orgId)).isEqualTo(1);
  }

  private <T> T getUnchecked(Future<T> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void expiredRecordIsTreatedAsNoRecord() throws Exception {
    String token = registerAndGetAccessToken();
    String key = "key-" + System.nanoTime();
    UUID orgId = organizationIdOf(token);
    String payload = objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR"));

    insertExpiredRecord(orgId, key, "/api/v1/expenses");

    postExpense(token, key, payload).andExpect(status().isCreated());
  }

  private ResultActions postExpense(String token, String key, String payload) throws Exception {
    return mockMvc.perform(
        post("/api/v1/expenses")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload));
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "expense-user-" + System.nanoTime() + "@example.com";
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
    AuthResponse response =
        objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    return response.accessToken();
  }

  private UUID organizationIdOf(String accessToken) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  private void insertInProgressRecord(UUID orgId, String key, String endpoint) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO idempotency_record "
                + "(organization_id, key, endpoint, request_hash, status, expires_at) "
                + "VALUES (?, ?, ?, 'irrelevant-hash', 'IN_PROGRESS', ?)")) {
      ps.setObject(1, orgId);
      ps.setString(2, key);
      ps.setString(3, endpoint);
      ps.setTimestamp(4, Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)));
      ps.executeUpdate();
    }
  }

  private void insertExpiredRecord(UUID orgId, String key, String endpoint) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO idempotency_record "
                + "(organization_id, key, endpoint, request_hash, status, expires_at) "
                + "VALUES (?, ?, ?, 'irrelevant-hash', 'COMPLETED', ?)")) {
      ps.setObject(1, orgId);
      ps.setString(2, key);
      ps.setString(3, endpoint);
      ps.setTimestamp(4, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
      ps.executeUpdate();
    }
  }
}
