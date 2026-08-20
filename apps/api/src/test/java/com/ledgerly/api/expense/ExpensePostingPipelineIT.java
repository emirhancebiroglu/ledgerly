package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.category.CategoryRequest;
import com.ledgerly.api.document.DocumentQueuePoller;
import com.ledgerly.api.document.DocumentExtractionWorker;
import com.ledgerly.api.document.DocumentStatus;
import com.ledgerly.api.document.DocumentStatusTransitions;
import com.ledgerly.api.document.ExtractionClient;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import com.ledgerly.api.support.SqlStatementCounter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The full M6 T6/T7 pipeline through the real endpoints: upload → extract → categorize → post or
 * review. `ai`'s three calls (extract, embed-query, categorize) are all stubbed via their ports —
 * the same pattern {@code DocumentStatusPipelineIT} uses for extraction alone.
 */
@AutoConfigureMockMvc
@Import({
  ExpensePostingPipelineIT.StubExtractionConfig.class,
  ExpensePostingPipelineIT.StubCategorizationConfig.class,
  ExpensePostingPipelineIT.SynchronousAsyncConfig.class,
  ExpensePostingPipelineIT.SqlCounterConfig.class
})
@org.springframework.test.context.TestPropertySource(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "ledgerly.document.queue.interval-seconds=3600"
    })
class ExpensePostingPipelineIT extends AbstractPostgresIT {

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StubExtractionClient stubExtractionClient;
  @Autowired private StubCategorizationClient stubCategorizationClient;
  @Autowired private DocumentQueuePoller queuePoller;
  @Autowired private DocumentExtractionWorker documentExtractionWorker;
  @Autowired private DocumentStatusTransitions documentStatusTransitions;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void resetStubs() {
    stubExtractionClient.reset();
    stubCategorizationClient.reset();
  }

  @Test
  void aHighConfidenceCategorizationPostsABalancedLedgerTransaction() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    // No policy_chunk rows exist for this org, so retrieval short-circuits to an empty list —
    // a citation the model claims anyway is not one any real chunk produced, and must be
    // scrubbed to null rather than trusted verbatim.
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Travel", 0.92, "policy excerpt"));

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);

    UUID expenseId = expenseIdForDocument(documentId);
    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"))
        .andExpect(jsonPath("$.ledgerTransactionId").isNotEmpty())
        .andExpect(jsonPath("$.citation").doesNotExist());

    assertThat(ledgerEntryCountForExpense(expenseId)).isEqualTo(2);
    assertThat(netBalanceForExpense(expenseId)).isZero();
    assertThat(activityStages(documentId))
        .containsExactly("UPLOADED", "EXTRACTING", "CATEGORIZING", "DRAFTING_LEDGER", "POSTED");
  }

  @Test
  void automaticPostingEvaluatesTheMatchingBudgetAndCreatesAn80PercentAlert() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId = organizationIdOf(token);
    createCategory(token, "Travel");
    UUID categoryId = categoryIdFor(organizationId, "Travel");
    UUID budgetId = insertBudget(organizationId, categoryId, 15_125);
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Travel", 0.92, null));

    uploadAndProcess(token);

    Long alertCount =
        queryLong(
            "SELECT COUNT(*) FROM alert WHERE budget_id = ? AND threshold_percent = 80", budgetId);
    assertThat(alertCount).isEqualTo(1);
  }

  @Test
  void aCitationMatchingARetrievedPolicyChunkIsKept() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    createCategory(token, "Travel");
    insertPolicyChunk(orgId, "Travel expenses over 500 EUR require manager approval.");
    stubCategorizationClient.respondWith(
        documentId ->
            categorizeResponse(
                documentId,
                "Travel",
                0.92,
                "Travel expenses over 500 EUR require manager approval."));

    MvcResult uploaded = uploadAndProcess(token);
    UUID expenseId = expenseIdForDocument(documentIdOf(uploaded));

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.citation")
                .value("Travel expenses over 500 EUR require manager approval."));
  }

  @Test
  void aCitationNotMatchingAnyRetrievedChunkIsScrubbed() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    createCategory(token, "Travel");
    insertPolicyChunk(orgId, "Travel expenses over 500 EUR require manager approval.");
    // The model claims a citation that was never among the retrieved chunks.
    stubCategorizationClient.respondWith(
        documentId ->
            categorizeResponse(documentId, "Travel", 0.92, "a policy that does not exist"));

    MvcResult uploaded = uploadAndProcess(token);
    UUID expenseId = expenseIdForDocument(documentIdOf(uploaded));

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.citation").doesNotExist());
  }

  @Test
  void aLowConfidenceCategorizationRoutesToReviewWithNoLedgerEntry() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Travel", 0.4, null));
    long ledgerEntriesBefore = countRows("ledger_entry");

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);
    UUID expenseId = expenseIdForDocument(documentId);

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist());

    assertThat(countRows("ledger_entry")).isEqualTo(ledgerEntriesBefore);
  }

  @Test
  void confidenceExactlyAtTheThresholdPostsNormally() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    // Default threshold is 0.7 (application.yml); at-threshold is posted, not reviewed.
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Travel", 0.7, null));

    MvcResult uploaded = uploadAndProcess(token);
    UUID expenseId = expenseIdForDocument(documentIdOf(uploaded));

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.status").value("POSTED"));
  }

  @Test
  void categorizationServiceFailureCreatesAnUnclassifiedReviewItem() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    stubCategorizationClient.failWith(() -> new RuntimeException("ai unavailable"));
    long ledgerEntriesBefore = countRows("ledger_entry");

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);
    UUID expenseId = expenseIdForDocument(documentId);

    assertUnclassifiedReview(token, documentId, expenseId, ledgerEntriesBefore);
  }

  @Test
  void malformedCategorizationResponseCreatesAnUnclassifiedReviewItem() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    stubCategorizationClient.respondWith(documentId -> "not-json");
    long ledgerEntriesBefore = countRows("ledger_entry");

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);

    assertUnclassifiedReview(token, documentId, expenseIdForDocument(documentId), ledgerEntriesBefore);
  }

  @Test
  void unknownCategorizationCategoryCreatesAnUnclassifiedReviewItem() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Invented category", 0.99, null));
    long ledgerEntriesBefore = countRows("ledger_entry");

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);

    assertUnclassifiedReview(token, documentId, expenseIdForDocument(documentId), ledgerEntriesBefore);
  }

  @Test
  void missingCategoryTaxonomyCreatesAnUnclassifiedReviewItem() throws Exception {
    String token = registerAndGetAccessToken();
    long ledgerEntriesBefore = countRows("ledger_entry");

    MvcResult uploaded = uploadAndProcess(token);
    UUID documentId = documentIdOf(uploaded);

    assertUnclassifiedReview(token, documentId, expenseIdForDocument(documentId), ledgerEntriesBefore);
  }

  @Test
  void concurrentDuplicateUnclassifiedFallbackCreatesOnlyOneReviewItem() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId = organizationIdOf(token);
    UUID documentId = documentIdOf(upload(token).andExpect(status().isCreated()).andReturn());
    UUID actor = userIdFor(organizationId);
    long ledgerTransactionsBefore = countRows("ledger_transaction");
    ExtractionProposal proposal =
        new ExtractionProposal(
            documentId.toString(),
            "Contoso",
            "EUR",
            12_100,
            2_100,
            LocalDate.now().minusDays(3),
            List.of(
                new ExtractionProposal.Line("item a", 1_000L, 4_000),
                new ExtractionProposal.Line("item b", 1_000L, 6_000)),
            java.util.Map.of("vendor", 0.9),
            "fake-llm-v1",
            List.of(),
            null);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Expense>> futures =
          executor.invokeAll(
              List.of(
                  () ->
                      expensePostingService.recordUnclassifiedNeedsReview(
                          organizationId, documentId, actor, proposal),
                  () ->
                      expensePostingService.recordUnclassifiedNeedsReview(
                          organizationId, documentId, actor, proposal)));
      for (Future<Expense> future : futures) {
        assertThat(future.get(10, TimeUnit.SECONDS).getId()).isNotNull();
      }
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(expenseCountForDocument(documentId)).isEqualTo(1);
    assertThat(countRows("ledger_transaction")).isEqualTo(ledgerTransactionsBefore);
    assertThat(activityStages(documentId)).containsExactly("UPLOADED", "NEEDS_REVIEW");
  }

  private void assertUnclassifiedReview(
      String token, UUID documentId, UUID expenseId, long ledgerEntriesBefore) throws Exception {
    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.categoryId").doesNotExist())
        .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist())
        .andExpect(jsonPath("$.amountMinor").value(12_100))
        .andExpect(jsonPath("$.currency").value("EUR"));

    assertThat(countRows("ledger_entry")).isEqualTo(ledgerEntriesBefore);
    assertThat(activityStages(documentId))
        .containsExactly("UPLOADED", "EXTRACTING", "CATEGORIZING", "NEEDS_REVIEW");
  }

  @Test
  void completedDocumentStatementCountIsBoundedAsCategoryAndHistoryFixturesGrow() throws Exception {
    String token = registerAndGetAccessToken();
    UUID organizationId = organizationIdOf(token);
    insertClassificationFixture(organizationId, 10, 10);
    stubCategorizationClient.respondWith(
        documentId -> categorizeResponse(documentId, "Category-001", 0.92, null));

    long statementsWithTenRows = statementsForCompletedDocument(token);

    insertClassificationFixture(organizationId, 100, 100);
    long statementsWithOneHundredRows = statementsForCompletedDocument(token);

    assertThat(statementsWithTenRows).isPositive();
    assertThat(statementsWithOneHundredRows).isLessThanOrEqualTo(statementsWithTenRows + 2);
  }

  private void createCategory(String token, String name) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CategoryRequest(name))))
        .andExpect(status().isCreated());
  }

  /** Creates direct fixtures outside the counted path: one transaction backs all history rows. */
  private void insertClassificationFixture(UUID organizationId, int categoryTarget, int historyTarget)
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID userId = userIdFor(connection, organizationId);
        int categoryStart = (categoryTarget == 10) ? 1 : 11;
        try (PreparedStatement category =
            connection.prepareStatement(
                "INSERT INTO category (id, organization_id, name) VALUES (?, ?, ?)")) {
          for (int number = categoryStart; number <= categoryTarget; number++) {
            category.setObject(1, UUID.randomUUID());
            category.setObject(2, organizationId);
            category.setString(3, "Category-%03d".formatted(number));
            category.addBatch();
          }
          category.executeBatch();
        }

        UUID categoryId = categoryIdFor(connection, organizationId, "Category-001");
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        try (PreparedStatement account =
                connection.prepareStatement(
                    "INSERT INTO account (id, organization_id, name, account_type, currency) "
                        + "VALUES (?, ?, ?, 'EXPENSE', 'EUR')");
            PreparedStatement transaction =
                connection.prepareStatement(
                    "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at) "
                        + "VALUES (?, ?, 'EUR', now() - INTERVAL '1 day')");
            PreparedStatement entry =
                connection.prepareStatement(
                    "INSERT INTO ledger_entry (id, transaction_id, account_id, direction, "
                        + "native_amount_minor, native_currency, base_amount_minor, base_currency, fx_rate) "
                        + "VALUES (?, ?, ?, ?, 100, 'EUR', 100, 'EUR', 1)")) {
          account.setObject(1, accountId);
          account.setObject(2, organizationId);
          account.setString(3, "History fixture account " + UUID.randomUUID());
          account.executeUpdate();
          transaction.setObject(1, transactionId);
          transaction.setObject(2, organizationId);
          transaction.executeUpdate();
          for (String direction : List.of("DEBIT", "CREDIT")) {
            entry.setObject(1, UUID.randomUUID());
            entry.setObject(2, transactionId);
            entry.setObject(3, accountId);
            entry.setString(4, direction);
            entry.addBatch();
          }
          entry.executeBatch();
        }

        int historyStart = (historyTarget == 10) ? 1 : 11;
        try (PreparedStatement document =
                connection.prepareStatement(
                    "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
                        + "size_bytes, storage_key, content_hash, status) "
                        + "VALUES (?, ?, ?, 'history.pdf', 'application/pdf', 1, ?, ?, 'EXTRACTED')");
            PreparedStatement expense =
                connection.prepareStatement(
                    "INSERT INTO expense (id, organization_id, document_id, category_id, "
                        + "ledger_transaction_id, amount_minor, currency, categorization_confidence, status) "
                        + "VALUES (?, ?, ?, ?, ?, 100, 'EUR', 0.9, 'POSTED')")) {
          for (int number = historyStart; number <= historyTarget; number++) {
            UUID documentId = UUID.randomUUID();
            document.setObject(1, documentId);
            document.setObject(2, organizationId);
            document.setObject(3, userId);
            document.setString(4, "history-storage-" + UUID.randomUUID());
            document.setString(5, "history-hash-" + number + "-" + UUID.randomUUID());
            document.addBatch();

            expense.setObject(1, UUID.randomUUID());
            expense.setObject(2, organizationId);
            expense.setObject(3, documentId);
            expense.setObject(4, categoryId);
            expense.setObject(5, transactionId);
            expense.addBatch();
          }
          document.executeBatch();
          expense.executeBatch();
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private long statementsForCompletedDocument(String token) throws Exception {
    SqlStatementCounter.reset();
    MvcResult uploaded =
        upload(token)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
    UUID documentId = documentIdOf(uploaded);
    UUID organizationId = organizationIdOf(token);
    assertThat(documentStatusTransitions.claimDueDocument(documentId, organizationId, Instant.now()))
        .isTrue();
    documentExtractionWorker.extractAsync(documentId, organizationId, null);
    assertThat(documentStatusTransitions.load(documentId, organizationId).getStatus())
        .isEqualTo(DocumentStatus.EXTRACTED);
    assertThat(expenseCountForDocument(documentId)).isEqualTo(1);
    return SqlStatementCounter.executed();
  }

  private UUID userIdFor(Connection connection, UUID organizationId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM app_user WHERE organization_id = ? LIMIT 1")) {
      statement.setObject(1, organizationId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return (UUID) rows.getObject("id");
      }
    }
  }

  private UUID userIdFor(UUID organizationId) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      return userIdFor(connection, organizationId);
    }
  }

  private UUID categoryIdFor(Connection connection, UUID organizationId, String name) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM category WHERE organization_id = ? AND name = ?")) {
      statement.setObject(1, organizationId);
      statement.setString(2, name);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return (UUID) rows.getObject("id");
      }
    }
  }

  private UUID organizationIdOf(String accessToken) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }

  /** Matches {@link StubQueryEmbeddingClient}'s fixed query vector so {@code findNearest} returns it. */
  private void insertPolicyChunk(UUID organizationId, String chunkText) throws Exception {
    UUID policyDocumentId = UUID.randomUUID();
    UUID userId;
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "SELECT id FROM app_user WHERE organization_id = ? LIMIT 1")) {
      ps.setObject(1, organizationId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        userId = (UUID) rs.getObject("id");
      }
    }
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "INSERT INTO policy_document (id, organization_id, uploaded_by, filename, "
                    + "storage_key, content_hash, status) "
                    + "VALUES (?, ?, ?, 'policy.pdf', ?, 'hash', 'EMBEDDED')")) {
      ps.setObject(1, policyDocumentId);
      ps.setObject(2, organizationId);
      ps.setObject(3, userId);
      ps.setString(4, UUID.randomUUID().toString());
      ps.executeUpdate();
    }
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "INSERT INTO policy_chunk (organization_id, policy_document_id, chunk_index, "
                    + "chunk_text, embedding) VALUES (?, ?, 0, ?, '[0.1,0.2,0.3,0.4]'::vector)")) {
      ps.setObject(1, organizationId);
      ps.setObject(2, policyDocumentId);
      ps.setString(3, chunkText);
      ps.executeUpdate();
    }
  }

  private long countRows(String table) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private UUID insertBudget(UUID organizationId, UUID categoryId, long limitMinor) throws Exception {
    UUID budgetId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "INSERT INTO budget (id, organization_id, category_id, period, limit_minor, currency) "
                    + "VALUES (?, ?, ?, ?, ?, 'EUR')")) {
      ps.setObject(1, budgetId);
      ps.setObject(2, organizationId);
      ps.setObject(3, categoryId);
      ps.setString(4, YearMonth.now(ZoneOffset.UTC).toString());
      ps.setLong(5, limitMinor);
      ps.executeUpdate();
    }
    return budgetId;
  }

  private UUID categoryIdFor(UUID organizationId, String name) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "SELECT id FROM category WHERE organization_id = ? AND name = ?")) {
      ps.setObject(1, organizationId);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject("id");
      }
    }
  }

  private Long queryLong(String sql, UUID value) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setObject(1, value);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private UUID expenseIdForDocument(UUID documentId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement("SELECT id FROM expense WHERE document_id = ?")) {
      ps.setObject(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject("id");
      }
    }
  }

  private long expenseCountForDocument(UUID documentId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement("SELECT count(*) FROM expense WHERE document_id = ?")) {
      ps.setObject(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private List<String> activityStages(UUID documentId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT stage FROM document_activity WHERE document_id = ? ORDER BY id")) {
      statement.setObject(1, documentId);
      try (ResultSet rows = statement.executeQuery()) {
        java.util.ArrayList<String> stages = new java.util.ArrayList<>();
        while (rows.next()) {
          stages.add(rows.getString("stage"));
        }
        return stages;
      }
    }
  }

  private long ledgerEntryCountForExpense(UUID expenseId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger_entry le "
                    + "JOIN expense e ON e.ledger_transaction_id = le.transaction_id "
                    + "WHERE e.id = ?")) {
      ps.setObject(1, expenseId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long netBalanceForExpense(UUID expenseId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement ps =
            connection.prepareStatement(
                "SELECT SUM(CASE WHEN direction = 'DEBIT' THEN base_amount_minor ELSE "
                    + "-base_amount_minor END) FROM ledger_entry le "
                    + "JOIN expense e ON e.ledger_transaction_id = le.transaction_id "
                    + "WHERE e.id = ?")) {
      ps.setObject(1, expenseId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private UUID documentIdOf(MvcResult result) throws Exception {
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
  }

  private ResultActions upload(String token) throws Exception {
    return mockMvc.perform(
        multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", "invoice.pdf", null, REAL_PDF))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "key-" + System.nanoTime()));
  }

  private MvcResult uploadAndProcess(String token) throws Exception {
    MvcResult uploaded =
        upload(token)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
    queuePoller.processDueDocuments();
    return uploaded;
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "posting-user-" + System.nanoTime() + "@example.com";
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

  private static String validProposal(UUID documentId) {
    return """
        {"document_id":"%s","vendor":"Contoso","currency":"EUR","total_minor":12100,
         "tax_minor":2100,"document_date":"%s",
         "lines":[{"description":"item a","quantity":1000,"amount_minor":4000},
                  {"description":"item b","quantity":1000,"amount_minor":6000}],
         "confidence":{"vendor":0.9,"currency":0.99,"total_minor":0.95,"tax_minor":0.9,
                       "document_date":0.93},
         "model":"fake-llm-v1","warnings":[]}
        """
        .formatted(documentId, LocalDate.now().minusDays(3));
  }

  private static String categorizeResponse(
      UUID documentId, String category, double confidence, String citation) {
    String citationJson = citation == null ? "null" : "\"" + citation + "\"";
    return """
        {"document_id":"%s","category":"%s","confidence":%s,"citation":%s,"model":"fake-llm-v1"}
        """
        .formatted(documentId, category, confidence, citationJson);
  }

  static class StubExtractionClient implements ExtractionClient {
    private Function<UUID, String> responder = ExpensePostingPipelineIT::validProposal;

    void reset() {
      this.responder = ExpensePostingPipelineIT::validProposal;
    }

    @Override
    public String extract(UUID documentId, byte[] content, String contentType, String filename) {
      return responder.apply(documentId);
    }
  }

  static class StubCategorizationClient implements CategorizationClient {
    private Function<UUID, String> responder;
    private java.util.function.Supplier<RuntimeException> failure;

    void reset() {
      this.responder = null;
      this.failure = null;
    }

    void respondWith(Function<UUID, String> responder) {
      this.responder = responder;
      this.failure = null;
    }

    void failWith(java.util.function.Supplier<RuntimeException> failure) {
      this.failure = failure;
    }

    @Override
    public String categorize(
        UUID documentId,
        String vendor,
        String currency,
        long totalMinor,
        String documentDate,
        List<String> categories,
        List<String> policyChunkTexts) {
      if (failure != null) {
        throw failure.get();
      }
      return responder.apply(documentId);
    }
  }

  static class StubQueryEmbeddingClient implements QueryEmbeddingClient {
    @Override
    public String embedQuery(String text) {
      return """
          {"model":"fake-embedding-v1","embedding_dimensions":4,"embedding":[0.1,0.2,0.3,0.4]}
          """;
    }
  }

  @TestConfiguration
  static class StubExtractionConfig {
    @Bean
    @Primary
    StubExtractionClient stubExtractionClient() {
      return new StubExtractionClient();
    }
  }

  @TestConfiguration
  static class StubCategorizationConfig {
    @Bean
    @Primary
    StubCategorizationClient stubCategorizationClient() {
      return new StubCategorizationClient();
    }

    @Bean
    @Primary
    StubQueryEmbeddingClient stubQueryEmbeddingClient() {
      return new StubQueryEmbeddingClient();
    }
  }

  @TestConfiguration
  static class SynchronousAsyncConfig implements AsyncConfigurer {
    @Bean(com.ledgerly.api.AsyncConfig.DOCUMENT_PROCESSING_EXECUTOR)
    @Primary
    java.util.concurrent.Executor documentProcessingExecutor() {
      return new SyncTaskExecutor();
    }

    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
      return documentProcessingExecutor();
    }
  }

  @TestConfiguration
  static class SqlCounterConfig {
    @Bean
    static BeanPostProcessor countingDataSourceWrapper() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (beanName.equals("dataSource") && bean instanceof javax.sql.DataSource dataSource) {
            return SqlStatementCounter.wrap(dataSource);
          }
          return bean;
        }
      };
    }
  }
}
