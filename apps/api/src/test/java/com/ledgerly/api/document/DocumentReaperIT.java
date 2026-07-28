package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * T7 — the reaper reclaims a document stuck in {@code PROCESSING}, closing the M4 follow-up: a
 * crash between {@code markProcessing} and the outcome write left such a document stranded forever.
 *
 * <p>The API never lets a document sit in {@code PROCESSING} for real (the stub answers instantly),
 * so stuck state is fabricated directly via JDBC — the same pattern {@link DocumentSchemaIT} uses.
 */
@Import(DocumentReaperIT.FixedClockConfig.class)
@TestPropertySource(properties = "ledgerly.document.reaper.batch-size=3")
class DocumentReaperIT extends AbstractPostgresIT {

  private static final Instant FIXED_NOW = Instant.parse("2026-07-27T12:00:00Z");

  @Autowired private DataSource dataSource;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentStatusTransitions transitions;
  @Autowired private DocumentReaper reaper;

  @Test
  void aDocumentStuckInProcessingPastTheTimeoutIsReclaimedAsFailed() throws Exception {
    UUID documentId;
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      documentId =
          insertStuckDocument(connection, orgId, userId, FIXED_NOW.minusSeconds(600));
    }

    reaper.reclaimStuckDocuments();

    Document reclaimed = documentRepository.findById(documentId).orElseThrow();
    assertThat(reclaimed.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(reclaimed.getFailureReason()).contains("reclaimed by the reaper");
  }

  @Test
  void aDocumentInProcessingWithinTheTimeoutIsLeftAlone() throws Exception {
    UUID documentId;
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      // Well within the 300s default stuck-after window.
      documentId = insertStuckDocument(connection, orgId, userId, FIXED_NOW.minusSeconds(30));
    }

    reaper.reclaimStuckDocuments();

    assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.PROCESSING);
  }

  @Test
  void terminalDocumentsAreNeverTouchedByTheReaperRegardlessOfAge() throws Exception {
    UUID documentId;
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      documentId = insertDocumentWithStatus(
          connection, orgId, userId, "EXTRACTED", FIXED_NOW.minusSeconds(10_000));
    }

    reaper.reclaimStuckDocuments();

    assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.EXTRACTED);
  }

  @Test
  void reclaimingIsIdempotentAcrossTwoInstancesRacingTheSameDocument() throws Exception {
    UUID documentId;
    UUID orgId;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      documentId =
          insertStuckDocument(connection, orgId, userId, FIXED_NOW.minusSeconds(600));
    }

    Instant cutoff = FIXED_NOW.minus(java.time.Duration.ofSeconds(300));
    // Simulates two reaper instances racing the same row: the first call reclaims it, the second
    // (identical) call must be a no-op, not a second failure write or an exception.
    boolean first =
        transitions.reclaimStuckDocument(documentId, orgId, cutoff, FIXED_NOW, "first reaper");
    boolean second =
        transitions.reclaimStuckDocument(documentId, orgId, cutoff, FIXED_NOW, "second reaper");

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    Document reclaimed = documentRepository.findById(documentId).orElseThrow();
    assertThat(reclaimed.getFailureReason()).isEqualTo("first reaper");
  }

  @Test
  void aDocumentThatResolvesNormallyBetweenSelectAndUpdateIsNotClobbered() throws Exception {
    UUID documentId;
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      documentId =
          insertStuckDocument(connection, orgId, userId, FIXED_NOW.minusSeconds(600));
    }
    UUID orgId = documentRepository.findById(documentId).orElseThrow().getOrganizationId();

    // The document resolves normally (EXTRACTED) after the reaper's candidate scan would have
    // picked it up, but before its UPDATE runs.
    transitions.recordOutcome(
        documentId,
        orgId,
        "{}",
        new ProposalValidationResult(java.util.List.of()));

    Instant cutoff = FIXED_NOW.minus(java.time.Duration.ofSeconds(300));
    boolean reclaimed =
        transitions.reclaimStuckDocument(documentId, orgId, cutoff, FIXED_NOW, "reaper");

    assertThat(reclaimed).isFalse();
    assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.EXTRACTED);
  }

  @Test
  void oneSweepReclaimsAtMostTheConfiguredBatchSize() throws Exception {
    // batch-size is 3 (see @TestPropertySource); insert 5 stuck documents so one sweep cannot
    // possibly reclaim all of them if the cap is honored.
    java.util.List<UUID> documentIds = new java.util.ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      for (int i = 0; i < 5; i++) {
        documentIds.add(insertStuckDocument(connection, orgId, userId, FIXED_NOW.minusSeconds(600)));
      }
    }

    reaper.reclaimStuckDocuments();

    long reclaimedCount =
        documentIds.stream()
            .map(id -> documentRepository.findById(id).orElseThrow())
            .filter(d -> d.getStatus() == DocumentStatus.FAILED)
            .count();

    assertThat(reclaimedCount).isEqualTo(3);
  }

  private UUID insertAppUser(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO app_user (id, organization_id, email, password_hash) "
                + "VALUES (?, ?, ?, 'hash')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, "reaper-user-" + id + "@example.com");
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertStuckDocument(
      Connection connection, UUID orgId, UUID userId, Instant updatedAt) throws SQLException {
    return insertDocumentWithStatus(connection, orgId, userId, "PROCESSING", updatedAt);
  }

  private UUID insertDocumentWithStatus(
      Connection connection, UUID orgId, UUID userId, String status, Instant updatedAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash, status, updated_at) "
                + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 2048, ?, 'hash', ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setObject(3, userId);
      ps.setString(4, UUID.randomUUID().toString());
      ps.setString(5, status);
      ps.setObject(6, java.sql.Timestamp.from(updatedAt));
      ps.executeUpdate();
    }
    return id;
  }

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
  }
}
