package com.ledgerly.api.dashboard;

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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/v1/dashboard/summary}: M7a T3. Totals must sum org-scoped POSTED expenses in
 * minor units, never leak another org's figures, and hand back zeros rather than 500 for an org
 * with no data yet.
 */
@AutoConfigureMockMvc
class DashboardSummaryIT extends AbstractPostgresIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TimeZone originalDefaultTimeZone;

  @BeforeEach
  void captureDefaultTimeZone() {
    originalDefaultTimeZone = TimeZone.getDefault();
  }

  @AfterEach
  void restoreDefaultTimeZone() {
    TimeZone.setDefault(originalDefaultTimeZone);
  }

  @Test
  void monthBoundaryIsComputedInUtcRegardlessOfJvmDefaultTimeZone() throws Exception {
    // A host east of UTC (Europe/Istanbul, UTC+3) is what this codebase actually runs on. If a
    // date bound were ever built through the JVM default zone instead of UTC
    // (java.sql.Timestamp.valueOf(LocalDateTime) does exactly that), "start of this month" would
    // be computed 3 hours before the true UTC month boundary -- pulling the last 3 hours of last
    // month's spend into this month's total.
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));

    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);

    ZonedDateTime startOfThisMonthUtc =
        ZonedDateTime.now(ZoneOffset.UTC)
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
    // 1 hour before the true UTC boundary: still last month by UTC, so it must be excluded from
    // "this month". The buggy Europe/Istanbul-shifted bound would have already rolled over to
    // this month by this point (3-hour shift > 1-hour margin), wrongly including it.
    insertPostedExpenseAt(org, categoryId, "EUR", 4200, startOfThisMonthUtc.minusHours(1));

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalsThisMonth").isArray())
        .andExpect(jsonPath("$.totalsThisMonth.length()").value(0));
  }

  @Test
  void totalsEqualTheSumOfPostedExpensesInMinorUnits() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "EUR", 12345);
    insertPostedExpense(org, categoryId, "EUR", 6789);
    // A NEEDS_REVIEW expense must not contribute -- it never posted.
    insertExpense(org, categoryId, "EUR", 99999, "NEEDS_REVIEW");

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalsThisMonth[0].currency").value("EUR"))
        .andExpect(jsonPath("$.totalsThisMonth[0].amountMinor").value(12345 + 6789));
  }

  @Test
  void anOrganizationWithNoExpensesReturnsZeroesNot500() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalsThisMonth").isArray())
        .andExpect(jsonPath("$.totalsThisMonth.length()").value(0))
        .andExpect(jsonPath("$.reviewQueueCount").value(0))
        .andExpect(jsonPath("$.documentsProcessedToday").value(0))
        // No currency has ever posted, so there is no series to zero-fill -- an empty list, not
        // a 6-month series with a currency-less zero row.
        .andExpect(jsonPath("$.monthlySeries.length()").value(0));
  }

  @Test
  void anotherOrganizationsExpensesDoNotLeakIntoAnyFigure() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    UUID orgA = organizationIdOf(tokenA);
    UUID orgB = organizationIdOf(tokenB);
    UUID categoryA = createCategory(orgA);
    UUID categoryB = createCategory(orgB);
    insertPostedExpense(orgA, categoryA, "EUR", 1000);
    insertPostedExpense(orgB, categoryB, "EUR", 99999);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalsThisMonth[0].amountMinor").value(1000));
  }

  @Test
  void monthlySeriesIsContiguousAndIncludesZeroSpendMonthsForACurrencyThatHasEverPosted()
      throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "EUR", 4200);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.monthlySeries.length()").value(6))
        .andExpect(jsonPath("$.monthlySeries[0].currency").value("EUR"))
        .andExpect(jsonPath("$.monthlySeries[0].amountMinor").value(0))
        .andExpect(jsonPath("$.monthlySeries[5].currency").value("EUR"))
        .andExpect(jsonPath("$.monthlySeries[5].amountMinor").value(4200));
  }

  @Test
  void monthlySeriesGivesEachCurrencyItsOwnCompleteSeriesRatherThanSummingThem() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "EUR", 1000);
    insertPostedExpense(org, categoryId, "USD", 2000);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        // 2 currencies * 6 months = 12 points, never one summed point per month.
        .andExpect(jsonPath("$.monthlySeries.length()").value(12))
        .andExpect(
            jsonPath("$.monthlySeries[?(@.currency == 'EUR' && @.month == '" + currentMonth()
                    + "')].amountMinor")
                .value(1000))
        .andExpect(
            jsonPath("$.monthlySeries[?(@.currency == 'USD' && @.month == '" + currentMonth()
                    + "')].amountMinor")
                .value(2000));
  }

  @Test
  void aCurrencyMismatchAcrossExpensesIsReportedPerCurrencyNotSummed() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "EUR", 1000);
    insertPostedExpense(org, categoryId, "USD", 2000);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalsThisMonth.length()").value(2))
        .andExpect(jsonPath("$.totalsThisMonth[?(@.currency == 'EUR')].amountMinor").value(1000))
        .andExpect(jsonPath("$.totalsThisMonth[?(@.currency == 'USD')].amountMinor").value(2000));
  }

  @Test
  void categoryBreakdownReturnsOneRowPerCurrencyRatherThanSummingThem() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "TRY", 45000);
    insertPostedExpense(org, categoryId, "USD", 2000);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryBreakdown.length()").value(2))
        .andExpect(
            jsonPath("$.categoryBreakdown[?(@.currency == 'TRY')].amountMinor").value(45000))
        .andExpect(jsonPath("$.categoryBreakdown[?(@.currency == 'USD')].amountMinor").value(2000));
  }

  @Test
  void categoryBreakdownForASingleCurrencyOrgIsUnchangedFromBeforeCurrencyWasAdded()
      throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertPostedExpense(org, categoryId, "EUR", 7500);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryBreakdown.length()").value(1))
        .andExpect(jsonPath("$.categoryBreakdown[0].currency").value("EUR"))
        .andExpect(jsonPath("$.categoryBreakdown[0].amountMinor").value(7500));
  }

  @Test
  void reviewQueueCountReflectsOnlyNeedsReview() throws Exception {
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    UUID categoryId = createCategory(org);
    insertExpense(org, categoryId, "EUR", 1000, "NEEDS_REVIEW");
    insertExpense(org, categoryId, "EUR", 2000, "NEEDS_REVIEW");
    insertPostedExpense(org, categoryId, "EUR", 3000);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewQueueCount").value(2));
  }

  @Test
  void noTokenReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
  }

  @Test
  void documentsProcessedTodayCountsEveryTerminalStatusIncludingExtractionNeedsReview()
      throws Exception {
    // V21 renamed document.status = 'NEEDS_REVIEW' to 'EXTRACTION_NEEDS_REVIEW' and dropped the
    // old value from the CHECK constraint. A query still filtering on the dead literal would
    // silently undercount every document routed to extraction review.
    String token = registerAndGetAccessToken();
    UUID org = organizationIdOf(token);
    insertDocumentWithStatus(org, "EXTRACTED");
    insertDocumentWithStatus(org, "EXTRACTION_NEEDS_REVIEW");
    insertDocumentWithStatus(org, "FAILED");
    // Non-terminal statuses must not count as "processed".
    insertDocumentWithStatus(org, "PENDING");
    insertDocumentWithStatus(org, "PROCESSING");
    // Yesterday's document, terminal but out of the "today" window.
    insertDocumentWithStatusAt(
        org, "EXTRACTED", ZonedDateTime.now(ZoneOffset.UTC).minusDays(1));

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentsProcessedToday").value(3));
  }

  private void insertDocumentWithStatus(UUID orgId, String status) {
    insertDocumentWithStatusAt(orgId, status, ZonedDateTime.now(ZoneOffset.UTC));
  }

  private void insertDocumentWithStatusAt(UUID orgId, String status, ZonedDateTime updatedAt) {
    UUID userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM app_user WHERE organization_id = ?", UUID.class, orgId);
    jdbcTemplate.update(
        "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
            + "size_bytes, storage_key, content_hash, status, updated_at) "
            + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 100, ?, ?, ?, ?)",
        UUID.randomUUID(),
        orgId,
        userId,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        status,
        java.sql.Timestamp.from(updatedAt.toInstant()));
  }

  private void insertPostedExpense(UUID orgId, UUID categoryId, String currency, long amountMinor) {
    insertExpense(orgId, categoryId, currency, amountMinor, "POSTED");
  }

  private void insertPostedExpenseAt(
      UUID orgId, UUID categoryId, String currency, long amountMinor, ZonedDateTime createdAt) {
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
            + "currency, categorization_confidence, status, created_at) "
            + "VALUES (?, ?, 'Acme Corp', ?, ?, ?, 0.9, 'POSTED', ?)",
        orgId,
        documentId,
        categoryId,
        amountMinor,
        currency,
        java.sql.Timestamp.from(createdAt.toInstant()));
  }

  private void insertExpense(
      UUID orgId, UUID categoryId, String currency, long amountMinor, String status) {
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
            + "VALUES (?, ?, 'Acme Corp', ?, ?, ?, 0.9, ?)",
        orgId,
        documentId,
        categoryId,
        amountMinor,
        currency,
        status);
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
    String email = "dashboard-user-" + System.nanoTime() + "@example.com";
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
    SecretKey key =
        Keys.hmacShaKeyFor(
            "test-only-secret-not-for-production-use-0123456789".getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  private String currentMonth() {
    return java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
  }
}
