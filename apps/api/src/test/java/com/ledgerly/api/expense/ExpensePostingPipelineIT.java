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
import com.ledgerly.api.document.ExtractionClient;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
  ExpensePostingPipelineIT.SynchronousAsyncConfig.class
})
@org.springframework.test.context.TestPropertySource(
    properties = "spring.main.allow-bean-definition-overriding=true")
class ExpensePostingPipelineIT extends AbstractPostgresIT {

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StubExtractionClient stubExtractionClient;
  @Autowired private StubCategorizationClient stubCategorizationClient;
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

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();
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

    upload(token).andExpect(jsonPath("$.status").value("EXTRACTED"));

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

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();
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

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();
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

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();
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

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();
    UUID expenseId = expenseIdForDocument(documentIdOf(uploaded));

    mockMvc
        .perform(get("/api/v1/expenses/" + expenseId).header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.status").value("POSTED"));
  }

  @Test
  void categorizationFailureLeavesTheDocumentExtractedWithNoExpense() throws Exception {
    String token = registerAndGetAccessToken();
    createCategory(token, "Travel");
    stubCategorizationClient.failWith(() -> new RuntimeException("ai unavailable"));

    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("EXTRACTED")).andReturn();

    assertThat(expenseCountForDocument(documentIdOf(uploaded))).isZero();
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
}
