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
