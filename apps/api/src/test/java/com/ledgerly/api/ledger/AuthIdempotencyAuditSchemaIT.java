package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V5 (app_user.password_hash, idempotency_record, audit_log) applies cleanly against the same
 * Testcontainers Postgres that already carries the M2 schema and seed data (V1-V4), since {@link
 * AbstractPostgresIT} runs Flyway end to end on a fresh container for the whole class.
 */
class AuthIdempotencyAuditSchemaIT extends AbstractPostgresIT {

  @Autowired
  private DataSource dataSource;

  @Test
  void idempotencyRecordRejectsDuplicateOrgKeyEndpoint() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      insertIdempotencyRecord(connection, orgId, "k1", "/api/v1/expenses");

      assertThatThrownBy(
              () -> insertIdempotencyRecord(connection, orgId, "k1", "/api/v1/expenses"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void idempotencyRecordAllowsSameKeyOnDifferentEndpoint() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      insertIdempotencyRecord(connection, orgId, "k1", "/api/v1/expenses");
      insertIdempotencyRecord(connection, orgId, "k1", "/api/v1/documents");
    }
  }

  @Test
  void auditLogCapturesActorAndBeforeAfterJson() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      UUID orgId = insertOrganization(connection);
      UUID userId = insertAppUser(connection, orgId, "user@example.com", "hash");
      UUID correlationId = UUID.randomUUID();

      try (PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO audit_log "
              + "(organization_id, actor, action, entity_type, entity_id, before, after, "
              + "correlation_id) "
              + "VALUES (?, ?, 'CREATE', 'expense', ?, NULL, '{\"total\":100}'::jsonb, ?)")) {
        ps.setObject(1, orgId);
        ps.setObject(2, userId);
        ps.setObject(3, UUID.randomUUID());
        ps.setObject(4, correlationId);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }

      try (var ps = connection.prepareStatement(
          "SELECT count(*) FROM audit_log WHERE organization_id = ?")) {
        ps.setObject(1, orgId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
      }
    }
  }

  private UUID insertAppUser(Connection connection, UUID orgId, String email, String passwordHash)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO app_user (id, organization_id, email, password_hash) "
            + "VALUES (?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, email);
      ps.setString(4, passwordHash);
      ps.executeUpdate();
    }
    return id;
  }

  private void insertIdempotencyRecord(
      Connection connection, UUID orgId, String key, String endpoint) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO idempotency_record "
            + "(organization_id, key, endpoint, request_hash, status, expires_at) "
            + "VALUES (?, ?, ?, 'hash', 'IN_PROGRESS', now() + interval '24 hours')")) {
      ps.setObject(1, orgId);
      ps.setString(2, key);
      ps.setString(3, endpoint);
      ps.executeUpdate();
    }
  }
}
