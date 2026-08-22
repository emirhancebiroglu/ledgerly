package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link LowConfidenceAlertEvaluator}: exactly one alert per review-routed expense, carrying its
 * stored confidence, org-scoped, and never touching a directly posted expense. */
class LowConfidenceAlertEvaluatorIT extends AbstractPostgresIT {

  @Autowired private LowConfidenceAlertEvaluator evaluator;
  @Autowired private ExpenseRepository expenseRepository;
  @Autowired private AlertRepository alertRepository;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void reviewRoutedExpenseProducesExactlyOneAlertCitingItsConfidence() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID documentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      actor = insertUser(connection, orgId);
      documentId = insertDocument(connection, orgId, actor);
    }

    UUID expenseId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  Expense expense =
                      expenseRepository.save(
                          Expense.needsReview(
                              orgId,
                              documentId,
                              "Delta Airlines",
                              categoryId,
                              84_500L,
                              "EUR",
                              0.42,
                              null));
                  expenseRepository.flush();
                  evaluator.evaluate(expense, actor);
                  return expense.getId();
                });

    List<com.ledgerly.api.alert.Alert> alerts =
        alertRepository.findByOrganizationId(
            orgId, org.springframework.data.domain.PageRequest.of(0, 20));
    assertThat(alerts).hasSize(1);
    com.ledgerly.api.alert.Alert alert = alerts.get(0);
    assertThat(alert.getAlertType()).isEqualTo("LOW_CONFIDENCE");
    assertThat(alert.getExpenseId()).isEqualTo(expenseId);
    assertThat(alert.getCategorizationConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.42));
  }

  @Test
  void directlyPostedExpenseProducesNoLowConfidenceAlert() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID documentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      actor = insertUser(connection, orgId);
      documentId = insertDocument(connection, orgId, actor);
    }

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Expense expense =
                  expenseRepository.save(
                      Expense.posted(
                          orgId,
                          documentId,
                          "Acme Cloud",
                          categoryId,
                          null,
                          10_000L,
                          "EUR",
                          0.95,
                          null));
              expenseRepository.flush();
              // Posted expenses never route through LowConfidenceAlertEvaluator in production —
              // this proves the evaluator itself would not fabricate an alert if it somehow were.
              evaluator.evaluate(expense, actor);
            });

    assertThat(
            alertRepository
                .findByOrganizationId(orgId, org.springframework.data.domain.PageRequest.of(0, 20))
                .stream()
                .filter(a -> a.getAlertType().equals("LOW_CONFIDENCE")))
        .isEmpty();
  }

  @Test
  void repeatedEvaluationOfTheSameExpenseDoesNotDuplicateTheAlert() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID documentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      actor = insertUser(connection, orgId);
      documentId = insertDocument(connection, orgId, actor);
    }

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Expense expense =
                  expenseRepository.save(
                      Expense.needsReview(
                          orgId, documentId, "Rivera Print Co.", categoryId, 12_800L, "EUR", 0.5, null));
              expenseRepository.flush();
              evaluator.evaluate(expense, actor);
              evaluator.evaluate(expense, actor);
            });

    long lowConfidenceCount =
        alertRepository
            .findByOrganizationId(orgId, org.springframework.data.domain.PageRequest.of(0, 20))
            .stream()
            .filter(a -> a.getAlertType().equals("LOW_CONFIDENCE"))
            .count();
    assertThat(lowConfidenceCount).isEqualTo(1);
  }

  private UUID insertCategory(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO category (id, organization_id, name) VALUES (?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, "cat-" + id);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertDocument(Connection connection, UUID orgId, UUID uploadedBy) throws SQLException {
    UUID id = UUID.randomUUID();
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
}
