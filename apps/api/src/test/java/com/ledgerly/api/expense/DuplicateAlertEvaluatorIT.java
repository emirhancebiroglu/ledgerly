package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.alert.Alert;
import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link DuplicateAlertEvaluator}: exactly one alert citing the earlier expense per duplicate
 * upload, a non-duplicate posting produces none, repeated evaluation does not duplicate the
 * alert, and — the plan's most important guarantee — the posting completes regardless of what
 * detection does. */
class DuplicateAlertEvaluatorIT extends AbstractPostgresIT {

  @Autowired private DuplicateAlertEvaluator evaluator;
  @Autowired private ExpenseRepository expenseRepository;
  @Autowired private AlertRepository alertRepository;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void aDuplicateUploadProducesExactlyOneAlertReferencingTheEarlierExpense() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID earlier;
    UUID candidateDocumentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      earlier = insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      actor = insertUser(connection, orgId);
      candidateDocumentId = insertDocument(connection, orgId);
    }

    UUID candidateId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  Expense candidate =
                      expenseRepository.save(
                          Expense.posted(
                              orgId, candidateDocumentId, "Acme Corp", categoryId, null, 10_000L,
                              "EUR", 0.95, null, "INV-1", java.time.LocalDate.of(2026, 7, 20)));
                  expenseRepository.flush();
                  evaluator.evaluate(candidate, actor);
                  return candidate.getId();
                });

    List<Alert> alerts =
        alertRepository.findByOrganizationId(orgId, PageRequest.of(0, 20)).stream()
            .filter(a -> a.getAlertType().equals("DUPLICATE_SUSPECTED"))
            .toList();
    assertThat(alerts).hasSize(1);
    Alert alert = alerts.get(0);
    assertThat(alert.getExpenseId()).isEqualTo(candidateId);
    assertThat(alert.getMatchedExpenseId()).isEqualTo(earlier);
    assertThat(alert.getDuplicateTier()).isEqualTo("CONFIRMED");
  }

  @Test
  void theLedgerTransactionIsUnaffectedByADuplicateFinding() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidateDocumentId;
    UUID actor;
    UUID transactionId;
    // Explicit autoCommit(false) + commit(): the ledger's balance constraint is DEFERRABLE
    // INITIALLY DEFERRED (checked at commit, not per-statement), so inserting the transaction row
    // and its two balancing entries must land in one transaction — the default autoCommit mode
    // would commit (and fail the deferred check on) the bare transaction row before its entries
    // ever get inserted.
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      actor = insertUser(connection, orgId);
      candidateDocumentId = insertDocument(connection, orgId);
      transactionId = insertLedgerTransaction(connection, orgId);
      connection.commit();
    }

    Expense candidate =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  Expense e =
                      expenseRepository.save(
                          Expense.posted(
                              orgId, candidateDocumentId, "Acme Corp", categoryId, transactionId,
                              10_000L, "EUR", 0.95, null, "INV-1", java.time.LocalDate.of(2026, 7, 20)));
                  expenseRepository.flush();
                  evaluator.evaluate(e, actor);
                  return e;
                });

    assertThat(candidate.getLedgerTransactionId()).isEqualTo(transactionId);
    assertThat(candidate.getStatus()).isEqualTo(ExpenseStatus.POSTED);
  }

  @Test
  void repeatedEvaluationOfTheSameExpensePairDoesNotDuplicateTheAlert() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidateDocumentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      actor = insertUser(connection, orgId);
      candidateDocumentId = insertDocument(connection, orgId);
    }

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Expense candidate =
                  expenseRepository.save(
                      Expense.posted(
                          orgId, candidateDocumentId, "Acme Corp", categoryId, null, 10_000L,
                          "EUR", 0.95, null, "INV-1", java.time.LocalDate.of(2026, 7, 20)));
              expenseRepository.flush();
              evaluator.evaluate(candidate, actor);
              evaluator.evaluate(candidate, actor);
            });

    long duplicateAlertCount =
        alertRepository.findByOrganizationId(orgId, PageRequest.of(0, 20)).stream()
            .filter(a -> a.getAlertType().equals("DUPLICATE_SUSPECTED"))
            .count();
    assertThat(duplicateAlertCount).isEqualTo(1);
  }

  @Test
  void aNonDuplicatePostingProducesNoAlert() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidateDocumentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      actor = insertUser(connection, orgId);
      candidateDocumentId = insertDocument(connection, orgId);
    }

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Expense candidate =
                  expenseRepository.save(
                      Expense.posted(
                          orgId, candidateDocumentId, "Fresh Vendor", categoryId, null, 5_000L,
                          "EUR", 0.95, null, "INV-UNIQUE", java.time.LocalDate.of(2026, 7, 20)));
              expenseRepository.flush();
              evaluator.evaluate(candidate, actor);
            });

    long duplicateAlertCount =
        alertRepository.findByOrganizationId(orgId, PageRequest.of(0, 20)).stream()
            .filter(a -> a.getAlertType().equals("DUPLICATE_SUSPECTED"))
            .count();
    assertThat(duplicateAlertCount).isZero();
  }

  @Test
  void detectionFindingNothingActionableStillLeavesThePostingIntact() throws SQLException {
    // A blank vendor makes DuplicateMatcher.findDuplicate short-circuit via normalizeVendor
    // returning null before any query runs — proves the posting-side contract end to end for the
    // "detection did nothing" path. See DuplicateAlertEvaluatorTest for the complementary case —
    // a genuine RuntimeException thrown from inside detection — which is exercised with a mocked
    // matcher rather than fabricated against a live Postgres connection here.
    UUID orgId;
    UUID categoryId;
    UUID candidateDocumentId;
    UUID actor;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      actor = insertUser(connection, orgId);
      candidateDocumentId = insertDocument(connection, orgId);
    }
    Expense candidate =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  Expense e =
                      expenseRepository.save(
                          Expense.posted(
                              orgId, candidateDocumentId, "", categoryId, null, 5_000L, "EUR",
                              0.95, null, null, null));
                  expenseRepository.flush();
                  evaluator.evaluate(e, actor);
                  return e;
                });

    assertThat(candidate.getStatus()).isEqualTo(ExpenseStatus.POSTED);
    assertThat(expenseRepository.findById(candidate.getId())).isPresent();
  }

  private UUID insertExpense(
      Connection connection,
      UUID orgId,
      UUID categoryId,
      String vendor,
      long amountMinor,
      String currency,
      String invoiceNumber,
      String issueDate)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO expense (id, organization_id, document_id, category_id, vendor, "
                + "amount_minor, currency, categorization_confidence, status, invoice_number, "
                + "issue_date, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, 'POSTED', ?, ?::date, now())")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setObject(3, insertDocument(connection, orgId));
      ps.setObject(4, categoryId);
      ps.setString(5, vendor);
      ps.setLong(6, amountMinor);
      ps.setString(7, currency);
      ps.setString(8, invoiceNumber);
      ps.setString(9, issueDate);
      ps.executeUpdate();
    }
    return id;
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

  /** A balanced transaction (one DEBIT + one CREDIT entry of equal amount) — the ledger's
   * deferred balance constraint rejects an unbalanced transaction at commit, so a test standing
   * in a real {@code ledger_transaction_id} needs a genuinely balanced one, not just a bare row. */
  private UUID insertLedgerTransaction(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at) "
                + "VALUES (?, ?, 'EUR', now())")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.executeUpdate();
    }
    UUID debitAccount = insertAccount(connection, orgId, "EXPENSE");
    UUID creditAccount = insertAccount(connection, orgId, "LIABILITY");
    insertLedgerEntry(connection, id, debitAccount, "DEBIT");
    insertLedgerEntry(connection, id, creditAccount, "CREDIT");
    return id;
  }

  private UUID insertAccount(Connection connection, UUID orgId, String accountType) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO account (id, organization_id, name, account_type, currency) "
                + "VALUES (?, ?, ?, ?, 'EUR')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, "acct-" + id);
      ps.setString(4, accountType);
      ps.executeUpdate();
    }
    return id;
  }

  private void insertLedgerEntry(Connection connection, UUID transactionId, UUID accountId, String direction)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO ledger_entry (id, transaction_id, account_id, direction, "
                + "native_amount_minor, native_currency, base_amount_minor, base_currency, fx_rate) "
                + "VALUES (?, ?, ?, ?, 10000, 'EUR', 10000, 'EUR', 1)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, transactionId);
      ps.setObject(3, accountId);
      ps.setString(4, direction);
      ps.executeUpdate();
    }
  }
}
