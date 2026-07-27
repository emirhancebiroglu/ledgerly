package com.ledgerly.api.expense;

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

/**
 * {@code GET /api/v1/expenses}: org scope, status filter, vendor search, sort. M7a T1 — the
 * dashboard's expenses list is the first reader of this endpoint.
 */
@AutoConfigureMockMvc
class ExpenseListIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void listReturnsOnlyTheCallersOrganization() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID orgB = organizationIdOf(tokenB);
    String categoryA = createCategory(orgA);
    String categoryB = createCategory(orgB);
    insertExpense(orgA, categoryA, "Acme Corp", 1000, "POSTED");
    insertExpense(orgB, categoryB, "Other Org Vendor", 2000, "POSTED");

    mockMvc
        .perform(get("/api/v1/expenses").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].vendor").value("Acme Corp"));
  }

  @Test
  void statusFilterExcludesOtherStatuses() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    String category = createCategory(org);
    insertExpense(org, category, "Posted Vendor", 1000, "POSTED");
    insertExpense(org, category, "Review Vendor", 2000, "NEEDS_REVIEW");

    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("status", "NEEDS_REVIEW")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].vendor").value("Review Vendor"));
  }

  @Test
  void sortByAmountOrdersNumericallyNotLexically() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    String category = createCategory(org);
    // Lexical order would put "200" before "30" before "4000"; numeric must not.
    insertExpense(org, category, "Vendor A", 30, "POSTED");
    insertExpense(org, category, "Vendor B", 4000, "POSTED");
    insertExpense(org, category, "Vendor C", 200, "POSTED");

    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("sort", "amount,asc")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].amountMinor").value(30))
        .andExpect(jsonPath("$[1].amountMinor").value(200))
        .andExpect(jsonPath("$[2].amountMinor").value(4000));

    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("sort", "amount,desc")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].amountMinor").value(4000))
        .andExpect(jsonPath("$[2].amountMinor").value(30));
  }

  @Test
  void searchIsCaseInsensitiveAndOnlyMatchesVendor() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    String category = createCategory(org);
    insertExpense(org, category, "Acme Corp", 1000, "POSTED");
    insertExpense(org, category, "Beta Industries", 2000, "POSTED");

    mockMvc
        .perform(
            get("/api/v1/expenses").param("search", "acme").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].vendor").value("Acme Corp"));

    // "POSTED" matches the status column's value, not a vendor name — must not match on it.
    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("search", "POSTED")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unknownSortFieldReturns400NotServerError() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("sort", "not-a-field,asc")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownStatusReturns400() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(
            get("/api/v1/expenses")
                .param("status", "NOT_A_STATUS")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void noTokenReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/expenses")).andExpect(status().isUnauthorized());
  }

  @Test
  void negativePageReturns400NotServerError() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/expenses").param("page", "-1").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nonPositiveSizeReturns400NotASilentDefault() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/expenses").param("size", "0").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/v1/expenses").param("size", "-5").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sizeIsClampedToTheMaximumPageSize() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    String category = createCategory(org);
    for (int i = 0; i < 5; i++) {
      insertExpense(org, category, "Vendor " + i, 1000 + i, "POSTED");
    }

    mockMvc
        .perform(get("/api/v1/expenses").param("size", "100000").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));
  }

  private void insertExpense(
      UUID orgId, String categoryId, String vendor, long amountMinor, String status) {
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
    jdbcTemplate.update(
        "INSERT INTO expense (organization_id, document_id, vendor, category_id, amount_minor, "
            + "currency, categorization_confidence, status) "
            + "VALUES (?, ?, ?, ?::uuid, ?, 'EUR', 0.9, ?)",
        orgId,
        documentId,
        vendor,
        categoryId,
        amountMinor,
        status);
  }

  private String createCategory(UUID orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO category (id, organization_id, name) VALUES (?, ?, ?)",
        id,
        orgId,
        "Category-" + id);
    return id.toString();
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "expense-list-user-" + System.nanoTime() + "@example.com";
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
