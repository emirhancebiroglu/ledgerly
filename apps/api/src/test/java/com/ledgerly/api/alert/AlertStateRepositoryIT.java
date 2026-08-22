package com.ledgerly.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** {@code alert_state}: composite key, per-user independence, and cascade-only mutation of alert
 * itself remains untouched by this table.
 *
 * <p>Deliberately not {@code @Transactional}: each test mixes plain JDBC inserts (via {@code
 * dataSource.getConnection()}) with JPA writes through {@code alertStateRepository}. Wrapping the
 * test in Spring's transaction would put the JPA writes on that transaction's connection while the
 * JDBC fixture inserts use separate, already-committed connections — but a later JDBC statement
 * (the cascade-delete test) would then block forever waiting on a lock held by the still-open,
 * never-committed JPA transaction. Every test mints fresh UUIDs, so no shared fixture state needs
 * transactional rollback for isolation. */
class AlertStateRepositoryIT extends AbstractPostgresIT {

  @Autowired private AlertStateRepository alertStateRepository;
  @Autowired private DataSource dataSource;

  @Test
  void duplicateCompositeKeyIsRejected() throws SQLException {
    UUID orgId;
    UUID userId;
    UUID alertId;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      userId = insertUser(connection, orgId);
      alertId = insertBudgetThresholdAlert(connection, orgId);
    }

    alertStateRepository.saveAndFlush(new AlertState(alertId, userId));

    assertThatThrownBy(() -> alertStateRepository.saveAndFlush(new AlertState(alertId, userId)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void twoUsersHoldIndependentStateForTheSameAlert() throws SQLException {
    UUID orgId;
    UUID userA;
    UUID userB;
    UUID alertId;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      userA = insertUser(connection, orgId);
      userB = insertUser(connection, orgId);
      alertId = insertBudgetThresholdAlert(connection, orgId);
    }

    AlertState stateA = new AlertState(alertId, userA);
    stateA.markRead();
    stateA.markDismissed();
    alertStateRepository.saveAndFlush(stateA);

    AlertState stateB = new AlertState(alertId, userB);
    alertStateRepository.saveAndFlush(stateB);

    AlertState reloadedA = alertStateRepository.findByAlertIdAndUserId(alertId, userA).orElseThrow();
    AlertState reloadedB = alertStateRepository.findByAlertIdAndUserId(alertId, userB).orElseThrow();
    assertThat(reloadedA.getReadAt()).isNotNull();
    assertThat(reloadedA.getDismissedAt()).isNotNull();
    assertThat(reloadedB.getReadAt()).isNull();
    assertThat(reloadedB.getDismissedAt()).isNull();
  }

  @Test
  void deletingAnAlertCascadesOnlyItsOwnStateRows() throws SQLException {
    UUID orgId;
    UUID userId;
    UUID alertToDelete;
    UUID alertToKeep;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      userId = insertUser(connection, orgId);
      alertToDelete = insertBudgetThresholdAlert(connection, orgId);
      alertToKeep = insertBudgetThresholdAlert(connection, orgId);
    }
    alertStateRepository.saveAndFlush(new AlertState(alertToDelete, userId));
    alertStateRepository.saveAndFlush(new AlertState(alertToKeep, userId));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement("DELETE FROM alert WHERE id = ?")) {
      ps.setObject(1, alertToDelete);
      ps.executeUpdate();
    }

    List<AlertState> remaining = alertStateRepository.findByUserId(userId);
    assertThat(remaining).extracting(AlertState::getAlertId).containsExactly(alertToKeep);
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

  private UUID insertBudgetThresholdAlert(Connection connection, UUID orgId) throws SQLException {
    UUID categoryId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO category (id, organization_id, name) VALUES (?, ?, ?)")) {
      ps.setObject(1, categoryId);
      ps.setObject(2, orgId);
      ps.setString(3, "cat-" + categoryId);
      ps.executeUpdate();
    }
    UUID expenseId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO expense (id, organization_id, document_id, category_id, "
                + "amount_minor, currency, categorization_confidence, status) "
                + "VALUES (?, ?, ?, ?, 1000, 'EUR', 0.9, 'POSTED')")) {
      ps.setObject(1, expenseId);
      ps.setObject(2, orgId);
      ps.setObject(3, insertDocument(connection, orgId));
      ps.setObject(4, categoryId);
      ps.executeUpdate();
    }
    UUID budgetId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO budget (id, organization_id, category_id, period, currency, limit_minor) "
                + "VALUES (?, ?, ?, '2026-08', 'EUR', 5000)")) {
      ps.setObject(1, budgetId);
      ps.setObject(2, orgId);
      ps.setObject(3, categoryId);
      ps.executeUpdate();
    }
    UUID alertId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO alert (id, organization_id, expense_id, budget_id, category_id, period, "
                + "currency, alert_type, threshold_percent, spent_minor, limit_minor, created_at) "
                + "VALUES (?, ?, ?, ?, ?, '2026-08', 'EUR', 'BUDGET_THRESHOLD', 80, 1000, 5000, ?)")) {
      ps.setObject(1, alertId);
      ps.setObject(2, orgId);
      ps.setObject(3, expenseId);
      ps.setObject(4, budgetId);
      ps.setObject(5, categoryId);
      ps.setObject(6, java.sql.Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }
    return alertId;
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
}
