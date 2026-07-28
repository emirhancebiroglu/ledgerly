package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /api/v1/expenses/{id}/detail}: M7a T4. The expense-detail screen's document viewer,
 * field grid, and ledger-entry rows all read from this one call.
 */
@AutoConfigureMockMvc
class ExpenseDetailIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void postedExpenseReturnsBothLedgerEntriesSummingToZero() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);

    UUID expenseAccount = insertAccount(org, "Travel", "EXPENSE");
    UUID liabilityAccount = insertAccount(org, "Accounts Payable", "LIABILITY");
    // The deferred balance-check trigger fires at the enclosing transaction's commit, not per
    // statement -- outside a real transaction each jdbcTemplate.update autocommits on its own, so
    // the header insert alone ("no entries yet") would fail immediately. Wrap all three writes in
    // one TransactionTemplate transaction so the trigger only checks once, after both entries exist.
    UUID transactionId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  UUID id = insertTransaction(org);
                  insertEntry(id, expenseAccount, "DEBIT", 5000);
                  insertEntry(id, liabilityAccount, "CREDIT", 5000);
                  return id;
                });

    UUID documentId = insertDocument(org);
    UUID expenseId =
        insertExpense(org, documentId, categoryId, transactionId, "Acme Corp", 5000, "POSTED");

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId + "/detail").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ledgerEntries.length()").value(2))
        .andExpect(jsonPath("$.ledgerEntries[0].direction").value("DEBIT"))
        .andExpect(jsonPath("$.ledgerEntries[1].direction").value("CREDIT"))
        .andExpect(jsonPath("$.document.filename").value("invoice.pdf"));

    // amountMinor is the native amount, always positive on both sides -- direction carries the
    // sign. Comparing the two magnitudes alone would pass even if both rows were DEBIT, so this
    // asserts each row's own direction+magnitude and sums them signed, the actual zero-sum claim.
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/expenses/" + expenseId + "/detail").header("Authorization", "Bearer " + token))
            .andReturn();
    var json = objectMapper.readTree(result.getResponse().getContentAsString());
    var entries = json.get("ledgerEntries");
    long signedSum = 0;
    for (var entry : entries) {
      long magnitude = entry.get("amountMinor").asLong();
      String direction = entry.get("direction").asText();
      assertThat(direction).isIn("DEBIT", "CREDIT");
      signedSum += direction.equals("DEBIT") ? magnitude : -magnitude;
    }
    assertThat(signedSum).isZero();
  }

  @Test
  void needsReviewExpenseReturnsEmptyLedgerEntriesNot500() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    UUID documentId = insertDocument(org);
    UUID expenseId = insertExpense(org, documentId, categoryId, null, "Acme Corp", 5000, "NEEDS_REVIEW");

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId + "/detail").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ledgerEntries").isArray())
        .andExpect(jsonPath("$.ledgerEntries.length()").value(0))
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));
  }

  @Test
  void anotherOrganizationsExpenseReturns404() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID categoryA = createCategory(orgA);
    UUID documentId = insertDocument(orgA);
    UUID expenseId = insertExpense(orgA, documentId, categoryA, null, "Acme Corp", 1000, "NEEDS_REVIEW");

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId + "/detail").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  private UUID insertAccount(UUID orgId, String name, String accountType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO account (id, organization_id, name, account_type, currency) "
            + "VALUES (?, ?, ?, ?, 'EUR')",
        id,
        orgId,
        name,
        accountType);
    return id;
  }

  private UUID insertTransaction(UUID orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at) "
            + "VALUES (?, ?, 'EUR', now())",
        id,
        orgId);
    return id;
  }

  private void insertEntry(UUID transactionId, UUID accountId, String direction, long amountMinor) {
    jdbcTemplate.update(
        "INSERT INTO ledger_entry (id, transaction_id, account_id, direction, "
            + "native_amount_minor, native_currency, base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, 'EUR', ?, 'EUR', 1)",
        UUID.randomUUID(),
        transactionId,
        accountId,
        direction,
        amountMinor,
        amountMinor);
  }

  private UUID insertDocument(UUID orgId) {
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
    return documentId;
  }

  private UUID insertExpense(
      UUID orgId,
      UUID documentId,
      UUID categoryId,
      UUID ledgerTransactionId,
      String vendor,
      long amountMinor,
      String status) {
    UUID expenseId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO expense (id, organization_id, document_id, vendor, category_id, "
            + "ledger_transaction_id, amount_minor, currency, categorization_confidence, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'EUR', 0.9, ?)",
        expenseId,
        orgId,
        documentId,
        vendor,
        categoryId,
        ledgerTransactionId,
        amountMinor,
        status);
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
    String email = "expense-detail-user-" + System.nanoTime() + "@example.com";
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
