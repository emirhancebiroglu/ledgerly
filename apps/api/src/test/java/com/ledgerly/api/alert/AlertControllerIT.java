package com.ledgerly.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.JwtService;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.category.CategoryRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code /api/v1/alerts}: type filter validation, dismissed-hidden-per-user visibility,
 * idempotent read/dismiss, org-scoped 404, and pagination. */
@AutoConfigureMockMvc
class AlertControllerIT extends AbstractPostgresIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JwtService jwtService;

  @Test
  void unknownTypeFilterIsRejected() throws Exception {
    String token = registerAndGetAccessToken();
    mockMvc
        .perform(get("/api/v1/alerts?type=NOT_A_TYPE").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void dismissingAnAlertHidesItForThatUserButNotAnotherAndItStaysInTheDatabase() throws Exception {
    UserSession userA = registerAndGetSession();
    UserSession userB = registerAndGetSession(userA.organizationId(), userA.categoryId());
    UUID alertId = insertLowConfidenceAlert(userA.organizationId(), userA.categoryId());

    mockMvc
        .perform(
            post("/api/v1/alerts/" + alertId + "/dismiss")
                .header("Authorization", "Bearer " + userA.token())
                .header("Idempotency-Key", "dismiss-" + System.nanoTime()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/alerts").header("Authorization", "Bearer " + userA.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mockMvc
        .perform(get("/api/v1/alerts").header("Authorization", "Bearer " + userB.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(alertId.toString()));

    Integer alertRowCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM alert WHERE id = ?", Integer.class, alertId);
    assertThat(alertRowCount).isEqualTo(1);
  }

  @Test
  void markingReadTwiceIsIdempotentAndReflectsInTheListing() throws Exception {
    UserSession user = registerAndGetSession();
    UUID alertId = insertLowConfidenceAlert(user.organizationId(), user.categoryId());

    mockMvc
        .perform(
            post("/api/v1/alerts/" + alertId + "/read")
                .header("Authorization", "Bearer " + user.token())
                .header("Idempotency-Key", "read-1-" + System.nanoTime()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/v1/alerts/" + alertId + "/read")
                .header("Authorization", "Bearer " + user.token())
                .header("Idempotency-Key", "read-2-" + System.nanoTime()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/alerts").header("Authorization", "Bearer " + user.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].read").value(true));

    Integer stateRowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM alert_state WHERE alert_id = ?", Integer.class, alertId);
    assertThat(stateRowCount).isEqualTo(1);
  }

  @Test
  void aForeignOrganizationsAlertIdIsNotFoundRatherThanActedOn() throws Exception {
    UserSession userA = registerAndGetSession();
    UserSession userB = registerAndGetSession();
    UUID alertOwnedByA = insertLowConfidenceAlert(userA.organizationId(), userA.categoryId());

    mockMvc
        .perform(
            post("/api/v1/alerts/" + alertOwnedByA + "/read")
                .header("Authorization", "Bearer " + userB.token())
                .header("Idempotency-Key", "foreign-read-" + System.nanoTime()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/v1/alerts/" + alertOwnedByA + "/dismiss")
                .header("Authorization", "Bearer " + userB.token())
                .header("Idempotency-Key", "foreign-dismiss-" + System.nanoTime()))
        .andExpect(status().isNotFound());

    Integer stateRowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM alert_state WHERE alert_id = ?", Integer.class, alertOwnedByA);
    assertThat(stateRowCount).isZero();
  }

  @Test
  void aDuplicateSuspectedAlertCarriesBothEntriesRealFigures() throws Exception {
    UserSession user = registerAndGetSession();
    UUID matchedExpenseId;
    UUID triggeringExpenseId;
    try (Connection connection = dataSource.getConnection()) {
      matchedExpenseId = insertExpense(connection, user.organizationId(), user.categoryId(), "Office Depot", 12_800);
      triggeringExpenseId = insertExpense(connection, user.organizationId(), user.categoryId(), "Office Depot", 89_900);
    }
    UUID alertId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO alert (id, organization_id, expense_id, category_id, period, currency, "
            + "alert_type, matched_expense_id, duplicate_tier, created_at) "
            + "VALUES (?, ?, ?, ?, '2026-08', 'EUR', 'DUPLICATE_SUSPECTED', ?, 'CONFIRMED', ?)",
        alertId,
        user.organizationId(),
        triggeringExpenseId,
        user.categoryId(),
        matchedExpenseId,
        java.sql.Timestamp.from(Instant.now()));

    mockMvc
        .perform(get("/api/v1/alerts").header("Authorization", "Bearer " + user.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].matchedExpenseId").value(matchedExpenseId.toString()))
        .andExpect(jsonPath("$[0].matchedExpense.vendor").value("Office Depot"))
        .andExpect(jsonPath("$[0].matchedExpense.amountMinor").value("12800"))
        .andExpect(jsonPath("$[0].triggeringExpense.vendor").value("Office Depot"))
        .andExpect(jsonPath("$[0].triggeringExpense.amountMinor").value("89900"));
  }

  @Test
  void aDuplicateSuspectedAlertDegradesGracefullyWhenTheMatchedExpenseWasDeleted() throws Exception {
    UserSession user = registerAndGetSession();
    UUID matchedExpenseId;
    UUID triggeringExpenseId;
    try (Connection connection = dataSource.getConnection()) {
      matchedExpenseId = insertExpense(connection, user.organizationId(), user.categoryId(), "Office Depot", 12_800);
      triggeringExpenseId = insertExpense(connection, user.organizationId(), user.categoryId(), "Office Depot", 89_900);
    }
    UUID alertId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO alert (id, organization_id, expense_id, category_id, period, currency, "
            + "alert_type, matched_expense_id, duplicate_tier, created_at) "
            + "VALUES (?, ?, ?, ?, '2026-08', 'EUR', 'DUPLICATE_SUSPECTED', ?, 'SUSPECTED', ?)",
        alertId,
        user.organizationId(),
        triggeringExpenseId,
        user.categoryId(),
        matchedExpenseId,
        java.sql.Timestamp.from(Instant.now()));
    jdbcTemplate.update("DELETE FROM expense WHERE id = ?", matchedExpenseId);

    mockMvc
        .perform(get("/api/v1/alerts").header("Authorization", "Bearer " + user.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].matchedExpenseId").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$[0].matchedExpense").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$[0].triggeringExpense.vendor").value("Office Depot"));
  }

  @Test
  void paginationStillBoundsTheAlertsListed() throws Exception {
    UserSession user = registerAndGetSession();
    for (int i = 0; i < 3; i++) {
      insertLowConfidenceAlert(user.organizationId(), user.categoryId());
    }

    mockMvc
        .perform(get("/api/v1/alerts?page=0&size=2").header("Authorization", "Bearer " + user.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  private record UserSession(String token, UUID organizationId, UUID categoryId) {}

  /** Registers a brand-new organization and its first user via the real HTTP flow. */
  private UserSession registerAndGetSession() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId =
        jdbcTemplate.queryForObject(
            "SELECT organization_id FROM app_user ORDER BY created_at DESC LIMIT 1", UUID.class);
    String categoryId = createCategory(token, "Travel");
    return new UserSession(token, organizationId, UUID.fromString(categoryId));
  }

  /** A second coworker in an already-existing organization. Inserted directly (JDBC) rather than
   * through {@code /auth/register}, which always provisions a brand-new organization — there is
   * no product flow for "invite a teammate into my org" yet, so this is the most direct way to
   * get two sessions that share one tenant. The access token is minted with the same {@link
   * JwtService} the real login path uses, so the controller sees a normal authenticated request. */
  private UserSession registerAndGetSession(UUID sharedOrganizationId, UUID categoryId) throws SQLException {
    UUID userId;
    try (Connection connection = dataSource.getConnection()) {
      userId = insertUser(connection, sharedOrganizationId);
    }
    String token = jwtService.issueAccessToken(userId, sharedOrganizationId);
    return new UserSession(token, sharedOrganizationId, categoryId);
  }

  private UUID insertLowConfidenceAlert(UUID orgId, UUID categoryId) throws SQLException {
    UUID expenseId;
    try (Connection connection = dataSource.getConnection()) {
      expenseId = insertExpense(connection, orgId, categoryId);
    }
    UUID alertId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO alert (id, organization_id, expense_id, category_id, period, currency, "
            + "alert_type, categorization_confidence, created_at) "
            + "VALUES (?, ?, ?, ?, '2026-08', 'EUR', 'LOW_CONFIDENCE', 0.4, ?)",
        alertId,
        orgId,
        expenseId,
        categoryId,
        java.sql.Timestamp.from(Instant.now()));
    return alertId;
  }

  private UUID insertExpense(Connection connection, UUID orgId, UUID categoryId) throws SQLException {
    return insertExpense(connection, orgId, categoryId, "Test Vendor", 1000);
  }

  private UUID insertExpense(
      Connection connection, UUID orgId, UUID categoryId, String vendor, long amountMinor)
      throws SQLException {
    UUID documentId = insertDocument(connection, orgId);
    UUID expenseId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO expense (id, organization_id, document_id, category_id, vendor, amount_minor, "
                + "currency, categorization_confidence, status) VALUES (?, ?, ?, ?, ?, ?, 'EUR', 0.4, 'NEEDS_REVIEW')")) {
      ps.setObject(1, expenseId);
      ps.setObject(2, orgId);
      ps.setObject(3, documentId);
      ps.setObject(4, categoryId);
      ps.setString(5, vendor);
      ps.setLong(6, amountMinor);
      ps.executeUpdate();
    }
    return expenseId;
  }

  private UUID insertDocument(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    UUID uploadedBy = insertUser(connection, orgId);
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash, status) "
                + "VALUES (?, ?, ?, ?, 'application/pdf', 1, ?, ?, 'EXTRACTED')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setObject(3, uploadedBy);
      ps.setString(4, "doc-" + id + ".pdf");
      ps.setString(5, "doc-" + id);
      ps.setString(6, "hash-" + id);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO app_user (id, organization_id, full_name, email, password_hash) "
                + "VALUES (?, ?, 'Test User', ?, 'hash')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, "user-" + id + "@example.com");
      ps.executeUpdate();
    }
    return id;
  }

  private String createCategory(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "category-" + System.nanoTime())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CategoryRequest(name))))
            .andExpect(status().isCreated())
            .andReturn();
    return jsonId(result);
  }

  private String registerAndGetAccessToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(),
                                "alert-user-" + System.nanoTime() + "@example.com",
                                "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private String jsonId(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }
}
