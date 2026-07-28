package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole M5 pipeline through the real endpoints: upload → durable queue → `ai` → validate →
 * status.
 *
 * <p>The `ai` call is the one thing stubbed, via the {@link ExtractionClient} port. That is
 * deliberate: the failure modes that matter most here — a timeout, a mismatched proposal, an
 * arithmetically broken one — are the ones that cannot be provoked reliably against a live service.
 *
 * <p>Extraction runs {@code @Async} in production after a queue poller atomically claims the row.
 * These tests invoke that poller directly and use {@link SynchronousAsyncConfig} so terminal
 * assertions do not need a sleep or a scheduler tick.
 */
@AutoConfigureMockMvc
@Import({DocumentStatusPipelineIT.StubExtractionConfig.class, DocumentStatusPipelineIT.SynchronousAsyncConfig.class})
@org.springframework.test.context.TestPropertySource(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "ledgerly.document.queue.interval-seconds=3600",
      "ledgerly.document.queue.max-attempts=2"
    })
class DocumentStatusPipelineIT extends AbstractPostgresIT {

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentQueuePoller queuePoller;
  @Autowired private DocumentStatusTransitions transitions;
  @Autowired private StubExtractionClient stubExtractionClient;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void resetStub() {
    stubExtractionClient.reset();
  }

  @Test
  void aValidDocumentReachesExtractedWithTheProposalAttached() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(DocumentStatusPipelineIT::validProposal);

    MvcResult result = upload(token).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    UUID documentId = documentIdOf(result);
    Document stored = documentRepository.findById(documentId).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
    assertThat(stored.getFailureReason()).isNull();
    // Assert on the parsed value, not the serialized text: whitespace is the mapper's business.
    assertThat(objectMapper.readTree(stored.getProposal()).get("total_minor").asLong())
        .isEqualTo(12_100L);
    assertThat(objectMapper.readTree(stored.getProposal()).get("document_id").asText())
        .isEqualTo(documentId.toString());
  }

  @Test
  void aCorruptProposalReachesNeedsReviewAndWritesNoLedgerEntry() throws Exception {
    String token = registerAndGetAccessToken();
    long ledgerEntriesBefore = countRows("ledger_entry");
    long ledgerTransactionsBefore = countRows("ledger_transaction");
    // Arithmetically broken: lines 10000 + tax 2100 is 12100, not 999999.
    stubExtractionClient.respondWith(id -> proposalWithTotal(id, 999_999L));

    MvcResult result = upload(token).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
    assertThat(stored.getFailureReason()).contains("does not equal");
    // The proposal is kept so a human reviewer can see what was actually claimed.
    assertThat(stored.getProposal()).isNotNull();

    assertThat(countRows("ledger_entry")).isEqualTo(ledgerEntriesBefore);
    assertThat(countRows("ledger_transaction")).isEqualTo(ledgerTransactionsBefore);
  }

  @Test
  void aValidProposalStillWritesNoLedgerEntryAtM4() throws Exception {
    String token = registerAndGetAccessToken();
    long ledgerEntriesBefore = countRows("ledger_entry");
    long ledgerTransactionsBefore = countRows("ledger_transaction");
    stubExtractionClient.respondWith(DocumentStatusPipelineIT::validProposal);

    upload(token).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    processQueue();

    assertThat(countRows("ledger_entry")).isEqualTo(ledgerEntriesBefore);
    assertThat(countRows("ledger_transaction")).isEqualTo(ledgerTransactionsBefore);
  }

  @Test
  void anUnknownCurrencyRoutesToNeedsReview() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(
        id -> validProposal(id).replace("\"currency\":\"EUR\"", "\"currency\":\"XXX\""));

    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    assertThat(documentRepository.findById(documentIdOf(result)).orElseThrow().getFailureReason())
        .contains("currency");
  }

  @Test
  void anAiTimeoutLeavesTheDocumentPendingForRetry() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.failWith(
        () -> new ExtractionUnavailableException("simulated read timeout"));

    MvcResult result = upload(token).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(stored.getProposal()).isNull();
    assertThat(stored.getFailureReason()).isEqualTo("Extraction service unavailable; retry scheduled");
  }

  @Test
  void aTransientOutageDoesNotHotLoopAndTheSameDocumentRecoversOnItsNextDueAttempt()
      throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.failWith(() -> new ExtractionUnavailableException("ai stopped"));
    MvcResult result =
        upload(token)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
    UUID documentId = documentIdOf(result);

    processQueue();
    Document afterOutage = documentRepository.findById(documentId).orElseThrow();
    assertThat(afterOutage.getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(afterOutage.getExtractionAttempts()).isEqualTo(1);
    assertThat(afterOutage.getNextAttemptAt()).isAfter(java.time.Instant.now().minusSeconds(1));

    processQueue();
    assertThat(stubExtractionClient.callCount()).isEqualTo(1);

    stubExtractionClient.respondWith(DocumentStatusPipelineIT::validProposal);
    makeRetryDue(documentId);
    processQueue();

    Document recovered = documentRepository.findById(documentId).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
    assertThat(recovered.getExtractionAttempts()).isEqualTo(2);
    assertThat(stubExtractionClient.callCount()).isEqualTo(2);
  }

  @Test
  void retriesStopAtTheConfiguredAttemptCap() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.failWith(() -> new ExtractionUnavailableException("ai stopped"));
    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    UUID documentId = documentIdOf(result);

    processQueue();
    makeRetryDue(documentId);
    processQueue();
    makeRetryDue(documentId);
    processQueue();

    Document exhausted = documentRepository.findById(documentId).orElseThrow();
    assertThat(exhausted.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(exhausted.getExtractionAttempts()).isEqualTo(2);
    assertThat(exhausted.getFailureReason()).isEqualTo("Extraction service unavailable after 2 attempts");
    assertThat(stubExtractionClient.callCount()).isEqualTo(2);
  }

  @Test
  void concurrentPollersCanClaimOneDueDocumentOnlyOnce() throws Exception {
    String token = registerAndGetAccessToken();
    UUID documentId = documentIdOf(upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn());
    Document document = documentRepository.findById(documentId).orElseThrow();
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.List<java.util.concurrent.Future<Boolean>> claims =
          java.util.List.of(
              executor.submit(() -> claimWhenReleased(document, ready, start)),
              executor.submit(() -> claimWhenReleased(document, ready, start)));
      assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(claims.stream().filter(this::resultOf).count()).isEqualTo(1);
      assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
          .isEqualTo(DocumentStatus.PROCESSING);
      assertThat(stubExtractionClient.callCount()).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aRejectedWorkerDispatchReturnsTheClaimToPendingWithoutSpendingAnAttempt() throws Exception {
    String token = registerAndGetAccessToken();
    UUID documentId =
        documentIdOf(upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn());
    DocumentExtractionWorker rejectingWorker =
        org.mockito.Mockito.mock(DocumentExtractionWorker.class);
    org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("executor full"))
        .when(rejectingWorker)
        .extractAsync(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    DocumentQueuePoller rejectingPoller =
        new DocumentQueuePoller(
            documentRepository,
            transitions,
            rejectingWorker,
            java.time.Clock.systemUTC(),
            100,
            5);

    rejectingPoller.processDueDocuments();

    Document released = documentRepository.findById(documentId).orElseThrow();
    assertThat(released.getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(released.getExtractionAttempts()).isZero();
    assertThat(released.getFailureReason())
        .isEqualTo("Extraction queue is busy; dispatch retry scheduled");
    assertThat(released.getNextAttemptAt()).isAfter(java.time.Instant.now().minusSeconds(1));
  }

  @Test
  void malformedJsonFromAiLeavesTheDocumentFailed() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(id -> "{this is not json");

    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();
    assertThat(stored.getFailureReason()).isEqualTo("Extraction returned a malformed proposal");
    assertThat(stored.getProposal()).isNull();
  }

  @Test
  void aFloatAmountFromAiLeavesTheDocumentFailedRatherThanBeingTruncated() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(
        id -> validProposal(id).replace("\"total_minor\":12100", "\"total_minor\":12100.5"));

    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    assertThat(documentRepository.findById(documentIdOf(result)).orElseThrow().getFailureReason())
        .isEqualTo("Extraction returned a malformed proposal");
  }

  @Test
  void aProposalAboutADifferentDocumentIsRefused() throws Exception {
    String token = registerAndGetAccessToken();
    // Well-formed and internally consistent, but about someone else's document.
    stubExtractionClient.respondWith(id -> validProposal(UUID.randomUUID()));

    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();
    assertThat(stored.getFailureReason()).isEqualTo("Extraction returned a mismatched proposal");
    assertThat(stored.getProposal()).isNull();
  }

  @Test
  void extractedIsTerminalSoProcessingCannotBeReentered() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(DocumentStatusPipelineIT::validProposal);
    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();

    assertThatThrownBy(() -> stored.transitionTo(DocumentStatus.PROCESSING))
        .isInstanceOf(IllegalDocumentTransitionException.class);
  }

  @Test
  void failedIsTerminalSoNoTransitionOutOfItIsAllowed() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(id -> "{malformed");
    MvcResult result = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();

    Document stored = documentRepository.findById(documentIdOf(result)).orElseThrow();

    for (DocumentStatus target : DocumentStatus.values()) {
      assertThatThrownBy(() -> stored.transitionTo(target))
          .isInstanceOf(IllegalDocumentTransitionException.class);
    }
  }

  @Test
  void theStatusIsReadableAfterwardsThroughTheGetEndpoint() throws Exception {
    String token = registerAndGetAccessToken();
    stubExtractionClient.respondWith(DocumentStatusPipelineIT::validProposal);
    MvcResult uploaded = upload(token).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
    processQueue();
    String documentId = documentIdOf(uploaded).toString();

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXTRACTED"))
        .andExpect(jsonPath("$.proposal.total_minor").value(12100));
  }

  private long countRows(String table) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
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

  private void processQueue() {
    queuePoller.processDueDocuments();
  }

  private void makeRetryDue(UUID documentId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        java.sql.PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE document SET next_attempt_at = now() - interval '1 second' WHERE id = ?")) {
      statement.setObject(1, documentId);
      statement.executeUpdate();
    }
  }

  private boolean claimWhenReleased(
      Document document,
      java.util.concurrent.CountDownLatch ready,
      java.util.concurrent.CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent claim did not start");
    }
    return transitions.claimDueDocument(
        document.getId(), document.getOrganizationId(), java.time.Instant.now());
  }

  private boolean resultOf(java.util.concurrent.Future<Boolean> claim) {
    try {
      return claim.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("Concurrent claim failed", e);
    }
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "pipeline-user-" + System.nanoTime() + "@example.com";
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
    return proposalWithTotal(documentId, 12_100L);
  }

  /** Lines total 10000 and tax is 2100, so only a total of 12100 is internally consistent. */
  private static String proposalWithTotal(UUID documentId, long totalMinor) {
    return """
        {"document_id":"%s","vendor":"Contoso","currency":"EUR","total_minor":%d,
         "tax_minor":2100,"document_date":"%s",
         "lines":[{"description":"item a","quantity":1000,"amount_minor":4000},
                  {"description":"item b","quantity":1000,"amount_minor":6000}],
         "confidence":{"vendor":0.9,"currency":0.99,"total_minor":0.95,"tax_minor":0.9,
                       "document_date":0.93},
         "model":"fake-llm-v1","warnings":[]}
        """
        .formatted(documentId, totalMinor, LocalDate.now().minusDays(3));
  }

  /** Replaces the real HTTP client so `ai`'s failure modes can be provoked deterministically. */
  static class StubExtractionClient implements ExtractionClient {

    private java.util.function.Function<UUID, String> responder = DocumentStatusPipelineIT::validProposal;
    private java.util.function.Supplier<RuntimeException> failure;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    void reset() {
      this.responder = DocumentStatusPipelineIT::validProposal;
      this.failure = null;
      this.calls.set(0);
    }

    void respondWith(java.util.function.Function<UUID, String> responder) {
      this.responder = responder;
      this.failure = null;
    }

    void failWith(java.util.function.Supplier<RuntimeException> failure) {
      this.failure = failure;
    }

    int callCount() {
      return calls.get();
    }

    @Override
    public String extract(UUID documentId, byte[] content, String contentType, String filename) {
      calls.incrementAndGet();
      if (failure != null) {
        throw failure.get();
      }
      return responder.apply(documentId);
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

  /**
   * Runs {@code @Async} work on the calling thread after the test explicitly polls the queue.
   */
  @TestConfiguration
  static class SynchronousAsyncConfig implements AsyncConfigurer {

    @Bean(com.ledgerly.api.AsyncConfig.DOCUMENT_PROCESSING_EXECUTOR)
    @Primary
    java.util.concurrent.Executor documentProcessingExecutor() {
      return new org.springframework.core.task.SyncTaskExecutor();
    }

    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
      return documentProcessingExecutor();
    }
  }
}
