package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * V8 (document) applies cleanly on top of V1-V7 against the shared Testcontainers Postgres, and the
 * table enforces the invariants the upload path depends on.
 */
class DocumentSchemaIT extends AbstractPostgresIT {

  @Autowired private DataSource dataSource;

  @Autowired private DocumentRepository documentRepository;

  @Test
  void statusDefaultsToPending() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);

      UUID documentId = insertDocumentWithoutStatus(connection, orgId, userId);

      assertThat(statusOf(connection, documentId)).isEqualTo("PENDING");
    }
  }

  @Test
  void storageKeyAndContentHashAreNonNull() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);

      assertThatThrownBy(() -> insertDocumentWithNullColumn(connection, orgId, userId, "storage_key"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () -> insertDocumentWithNullColumn(connection, orgId, userId, "content_hash"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void storageKeyIsUniqueAcrossTheTable() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);
      String sharedKey = UUID.randomUUID().toString();

      insertDocument(connection, orgId, userId, sharedKey);

      assertThatThrownBy(() -> insertDocument(connection, orgId, userId, sharedKey))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void anUnknownStatusIsRejectedByTheCheckConstraint() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);

      assertThatThrownBy(
              () -> {
                try (PreparedStatement ps =
                    connection.prepareStatement(
                        "INSERT INTO document (organization_id, uploaded_by, filename, "
                            + "content_type, size_bytes, storage_key, content_hash, status) "
                            + "VALUES (?, ?, 'x.pdf', 'application/pdf', 1, ?, 'h', 'BOGUS')")) {
                  ps.setObject(1, orgId);
                  ps.setObject(2, userId);
                  ps.setString(3, UUID.randomUUID().toString());
                  ps.executeUpdate();
                }
              })
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void aZeroOrNegativeSizeIsRejected() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId);

      assertThatThrownBy(() -> insertDocumentWithSize(connection, orgId, userId, 0))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertDocumentWithSize(connection, orgId, userId, -1))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void orgBsDocumentIdReturnsEmptyForOrgA() throws Exception {
    UUID orgA;
    UUID orgB;
    UUID orgBDocumentId;
    try (Connection connection = dataSource.getConnection()) {
      orgA = insertOrganization(connection);
      orgB = insertOrganization(connection);
      UUID userB = insertAppUser(connection, orgB);
      orgBDocumentId = insertDocument(connection, orgB, userB, UUID.randomUUID().toString());
    }

    assertThat(documentRepository.findByIdAndOrganizationId(orgBDocumentId, orgB)).isPresent();
    assertThat(documentRepository.findByIdAndOrganizationId(orgBDocumentId, orgA)).isEmpty();
  }

  @Test
  void theOrgScopedListingIsPagedAndNeverLeaksAnotherOrg() throws Exception {
    UUID orgA;
    UUID orgB;
    try (Connection connection = dataSource.getConnection()) {
      orgA = insertOrganization(connection);
      orgB = insertOrganization(connection);
      UUID userA = insertAppUser(connection, orgA);
      UUID userB = insertAppUser(connection, orgB);
      for (int i = 0; i < 3; i++) {
        insertDocument(connection, orgA, userA, UUID.randomUUID().toString());
      }
      insertDocument(connection, orgB, userB, UUID.randomUUID().toString());
    }

    assertThat(
            documentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 2)))
        .hasSize(2)
        .allSatisfy(document -> assertThat(document.getOrganizationId()).isEqualTo(orgA));
    assertThat(documentRepository.countByOrganizationId(orgA)).isEqualTo(3);
    assertThat(documentRepository.countByOrganizationId(orgB)).isEqualTo(1);
  }

  @Test
  void theDocumentTableHasNoFloatingPointColumn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps =
            connection.prepareStatement(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'document'
                  AND data_type IN ('real', 'double precision', 'float4', 'float8')
                """);
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).as("document must have no floating-point column").isFalse();
    }
  }

  private String statusOf(Connection connection, UUID documentId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT status FROM document WHERE id = ?")) {
      ps.setObject(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private UUID insertAppUser(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO app_user (id, organization_id, email, password_hash) "
                + "VALUES (?, ?, ?, 'hash')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, "user-" + id + "@example.com");
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertDocument(Connection connection, UUID orgId, UUID userId, String storageKey)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash) "
                + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 2048, ?, 'hash')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setObject(3, userId);
      ps.setString(4, storageKey);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertDocumentWithoutStatus(Connection connection, UUID orgId, UUID userId)
      throws SQLException {
    return insertDocument(connection, orgId, userId, UUID.randomUUID().toString());
  }

  private void insertDocumentWithSize(Connection connection, UUID orgId, UUID userId, long size)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash) "
                + "VALUES (?, ?, 'invoice.pdf', 'application/pdf', ?, ?, 'hash')")) {
      ps.setObject(1, orgId);
      ps.setObject(2, userId);
      ps.setLong(3, size);
      ps.setString(4, UUID.randomUUID().toString());
      ps.executeUpdate();
    }
  }

  private void insertDocumentWithNullColumn(
      Connection connection, UUID orgId, UUID userId, String nullColumn) throws SQLException {
    String storageKey = "storage_key".equals(nullColumn) ? null : UUID.randomUUID().toString();
    String contentHash = "content_hash".equals(nullColumn) ? null : "hash";
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash) "
                + "VALUES (?, ?, 'invoice.pdf', 'application/pdf', 10, ?, ?)")) {
      ps.setObject(1, orgId);
      ps.setObject(2, userId);
      ps.setString(3, storageKey);
      ps.setString(4, contentHash);
      ps.executeUpdate();
    }
  }
}
