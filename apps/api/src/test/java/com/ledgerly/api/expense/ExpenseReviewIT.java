package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /expenses/{id}/approve} and {@code .../correct}: M7a T2's review-queue resolution.
 * Both must post a balanced ledger transaction exactly once and flip the expense to POSTED.
 */
@AutoConfigureMockMvc
class ExpenseReviewIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void approvePostsABalancedTransactionAndFlipsStatus() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, categoryId, "Acme Corp", 5000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"))
        .andExpect(jsonPath("$.ledgerTransactionId").isNotEmpty());

    String transactionId =
        jdbcTemplate.queryForObject(
            "SELECT ledger_transaction_id FROM expense WHERE id = ?::uuid", String.class, expenseId.toString());
    Long balance =
        jdbcTemplate.queryForObject(
            "SELECT SUM(CASE WHEN direction = 'DEBIT' THEN base_amount_minor ELSE -base_amount_minor END) "
                + "FROM ledger_entry WHERE transaction_id = ?::uuid",
            Long.class,
            transactionId);
    assertThat(balance).isZero();
  }

  @Test
  void approvingAnAlreadyPostedExpenseReturns409AndCreatesNoSecondTransaction() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, categoryId, "Acme Corp", 5000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isConflict());

    Integer transactionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_transaction WHERE organization_id = ?", Integer.class, org);
    assertThat(transactionCount).isEqualTo(1);
  }

  @Test
  void correctRePointsTheDebitAccountAndStillBalances() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID originalCategory = createCategory(org);
    UUID correctedCategory = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, originalCategory, "Acme Corp", 3000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/correct")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new CorrectExpenseRequest(correctedCategory))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"))
        .andExpect(jsonPath("$.categoryId").value(correctedCategory.toString()));

    String transactionId =
        jdbcTemplate.queryForObject(
            "SELECT ledger_transaction_id FROM expense WHERE id = ?::uuid", String.class, expenseId.toString());
    Long balance =
        jdbcTemplate.queryForObject(
            "SELECT SUM(CASE WHEN direction = 'DEBIT' THEN base_amount_minor ELSE -base_amount_minor END) "
                + "FROM ledger_entry WHERE transaction_id = ?::uuid",
            Long.class,
            transactionId);
    assertThat(balance).isZero();
  }

  @Test
  void concurrentApprovesWithDifferentIdempotencyKeysPostExactlyOnce() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, categoryId, "Acme Corp", 5000);
    // Pre-create both ledger accounts resolve() looks up so this test isolates the concurrency
    // guarantee on expense resolution itself from LedgerAccountRepository.findOrCreate's own
    // first-use race, which is real and separately covered by LedgerAccountRepositoryIT.
    String categoryName =
        jdbcTemplate.queryForObject(
            "SELECT name FROM category WHERE id = ?", String.class, categoryId);
    jdbcTemplate.update(
        "INSERT INTO account (id, organization_id, name, account_type, currency) VALUES (?, ?, ?, 'EXPENSE', 'EUR')",
        UUID.randomUUID(),
        org,
        categoryName);
    jdbcTemplate.update(
        "INSERT INTO account (id, organization_id, name, account_type, currency) VALUES (?, ?, 'Accounts Payable', 'LIABILITY', 'EUR')",
        UUID.randomUUID(),
        org);

    // Different Idempotency-Key values are different claims as far as the idempotency filter is
    // concerned — it cannot dedup these. Only the atomic conditional UPDATE inside
    // ExpenseReviewTransactions.resolve can prevent both racing requests from posting their own
    // ledger transaction.
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Integer>> tasks =
          List.of(
              () ->
                  mockMvc
                      .perform(
                          post("/api/v1/expenses/" + expenseId + "/approve")
                              .header("Authorization", "Bearer " + token)
                              .header("Idempotency-Key", "race-key-a-" + System.nanoTime()))
                      .andReturn()
                      .getResponse()
                      .getStatus(),
              () ->
                  mockMvc
                      .perform(
                          post("/api/v1/expenses/" + expenseId + "/approve")
                              .header("Authorization", "Bearer " + token)
                              .header("Idempotency-Key", "race-key-b-" + System.nanoTime()))
                      .andReturn()
                      .getResponse()
                      .getStatus());

      List<Future<Integer>> futures = executor.invokeAll(tasks);
      int okCount = 0;
      int conflictCount = 0;
      for (Future<Integer> future : futures) {
        int status = future.get();
        if (status == 200) {
          okCount++;
        } else if (status == 409) {
          conflictCount++;
        }
      }

      assertThat(okCount).isEqualTo(1);
      assertThat(conflictCount).isEqualTo(1);
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    Integer transactionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_transaction WHERE organization_id = ?", Integer.class, org);
    assertThat(transactionCount).isEqualTo(1);
  }

  @Test
  void anotherOrganizationsExpenseReturns404NotAConflict() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID categoryA = createCategory(orgA);
    UUID expenseId = insertNeedsReviewExpense(orgA, categoryA, "Acme Corp", 1000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isNotFound());
  }

  @Test
  void replayedIdempotencyKeyReturnsTheOriginalResponseAndCreatesNoSecondTransaction()
      throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, categoryId, "Acme Corp", 5000);
    String key = "replay-key-" + System.nanoTime();

    MvcResult first =
        mockMvc
            .perform(
                post("/api/v1/expenses/" + expenseId + "/approve")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andReturn();

    MvcResult second =
        mockMvc
            .perform(
                post("/api/v1/expenses/" + expenseId + "/approve")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    Integer transactionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_transaction WHERE organization_id = ?", Integer.class, org);
    assertThat(transactionCount).isEqualTo(1);
  }

  @Test
  void approveWritesAnAuditRow() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, categoryId, "Acme Corp", 1000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isOk());

    Integer auditCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'expense' AND entity_id = ?::uuid "
                + "AND action = 'APPROVE'",
            Integer.class,
            expenseId.toString());
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void correctWritesAnAuditRow() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID originalCategory = createCategory(org);
    UUID correctedCategory = createCategory(org);
    UUID expenseId = insertNeedsReviewExpense(org, originalCategory, "Acme Corp", 1000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/correct")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new CorrectExpenseRequest(correctedCategory))))
        .andExpect(status().isOk());

    Integer auditCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'expense' AND entity_id = ?::uuid "
                + "AND action = 'CORRECT'",
            Integer.class,
            expenseId.toString());
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void correctWithACategoryFromAnotherOrganizationReturns404() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID orgB = organizationIdOf(tokenB);
    UUID categoryA = createCategory(orgA);
    UUID categoryB = createCategory(orgB);
    UUID expenseId = insertNeedsReviewExpense(orgA, categoryA, "Acme Corp", 1000);

    mockMvc
        .perform(
            post("/api/v1/expenses/" + expenseId + "/correct")
                .header("Authorization", "Bearer " + tokenA)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CorrectExpenseRequest(categoryB))))
        .andExpect(status().isNotFound());
  }

  @Test
  void noTokenReturns401() throws Exception {
    mockMvc
        .perform(post("/api/v1/expenses/" + UUID.randomUUID() + "/approve"))
        .andExpect(status().isUnauthorized());
  }

  private UUID insertNeedsReviewExpense(UUID orgId, UUID categoryId, String vendor, long amountMinor) {
    UUID userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM app_user WHERE organization_id = ?", UUID.class, orgId);
    UUID documentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
            + "size_bytes, storage_key, content_hash, status) "
            + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 100, ?, 'hash', 'EXTRACTED')",
        documentId,
        orgId,
        userId,
        UUID.randomUUID().toString());
    UUID expenseId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO expense (id, organization_id, document_id, vendor, category_id, "
            + "amount_minor, currency, categorization_confidence, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'EUR', 0.5, 'NEEDS_REVIEW')",
        expenseId,
        orgId,
        documentId,
        vendor,
        categoryId,
        amountMinor);
    return expenseId;
  }

  private UUID createCategory(UUID orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO category (id, organization_id, name) VALUES (?, ?, ?)",
        id,
        orgId,
        "Category-" + id);
    return id;
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "expense-review-user-" + System.nanoTime() + "@example.com";
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
