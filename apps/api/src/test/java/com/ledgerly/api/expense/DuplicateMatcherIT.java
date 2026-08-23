package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link DuplicateMatcher}: exact invoice-number matches, the windowed amount heuristic when no
 * invoice number is readable, and every boundary the plan's test criteria name explicitly. */
class DuplicateMatcherIT extends AbstractPostgresIT {

  @Autowired private DuplicateMatcher matcher;
  @Autowired private ExpenseRepository expenseRepository;
  @Autowired private DataSource dataSource;

  @Test
  void identicalVendorAndInvoiceNumberMatchesAsConfirmedRegardlessOfAmount() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID earlier;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      earlier =
          insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      candidate =
          insertExpense(connection, orgId, categoryId, "Acme Corp", 99_999L, "EUR", "INV-1", "2026-07-20");
    }

    Optional<DuplicateMatch> match = matcher.findDuplicate(loadExpense(candidate));

    assertThat(match).isPresent();
    assertThat(match.get().matchedExpenseId()).isEqualTo(earlier);
    assertThat(match.get().tier()).isEqualTo(DuplicateMatchTier.CONFIRMED);
  }

  @Test
  void differingInvoiceNumberNeverMatchesEvenWithIdenticalAmountAndDate() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      candidate =
          insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-2", "2026-07-01");
    }

    assertThat(matcher.findDuplicate(loadExpense(candidate))).isEmpty();
  }

  @Test
  void noInvoiceNumberSameVendorAmountAndCurrencyAtSevenDaysMatchesAsSuspected() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID earlier;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      earlier = insertExpense(connection, orgId, categoryId, "Rivera Print Co.", 12_800L, "EUR", null, "2026-07-01");
      candidate = insertExpense(connection, orgId, categoryId, "Rivera Print Co.", 12_800L, "EUR", null, "2026-07-08");
    }

    Optional<DuplicateMatch> match = matcher.findDuplicate(loadExpense(candidate));

    assertThat(match).isPresent();
    assertThat(match.get().matchedExpenseId()).isEqualTo(earlier);
    assertThat(match.get().tier()).isEqualTo(DuplicateMatchTier.SUSPECTED);
  }

  @Test
  void noInvoiceNumberAtEightDaysDoesNotMatch() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "Rivera Print Co.", 12_800L, "EUR", null, "2026-07-01");
      candidate = insertExpense(connection, orgId, categoryId, "Rivera Print Co.", 12_800L, "EUR", null, "2026-07-09");
    }

    assertThat(matcher.findDuplicate(loadExpense(candidate))).isEmpty();
  }

  @Test
  void aMonthlyRecurringChargeThirtyDaysApartDoesNotMatch() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "SaaS Vendor", 5_000L, "EUR", null, "2026-06-01");
      candidate = insertExpense(connection, orgId, categoryId, "SaaS Vendor", 5_000L, "EUR", null, "2026-07-01");
    }

    assertThat(matcher.findDuplicate(loadExpense(candidate))).isEmpty();
  }

  @Test
  void vendorComparisonIsCaseAndWhitespaceInsensitive() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID earlier;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      earlier = insertExpense(connection, orgId, categoryId, "  Acme Corp  ", 10_000L, "EUR", "INV-9", "2026-07-01");
      candidate = insertExpense(connection, orgId, categoryId, "ACME CORP", 20_000L, "EUR", "INV-9", "2026-07-20");
    }

    Optional<DuplicateMatch> match = matcher.findDuplicate(loadExpense(candidate));

    assertThat(match).isPresent();
    assertThat(match.get().matchedExpenseId()).isEqualTo(earlier);
  }

  @Test
  void aDifferentCurrencyAtTheSameNumericAmountNeverMatches() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      insertExpense(connection, orgId, categoryId, "Global Vendor", 10_000L, "EUR", null, "2026-07-01");
      candidate = insertExpense(connection, orgId, categoryId, "Global Vendor", 10_000L, "USD", null, "2026-07-01");
    }

    assertThat(matcher.findDuplicate(loadExpense(candidate))).isEmpty();
  }

  @Test
  void aForeignOrganizationsExpenseIsNeverReturned() throws SQLException {
    UUID orgA;
    UUID orgB;
    UUID categoryA;
    UUID categoryB;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgA = insertOrganization(connection);
      orgB = insertOrganization(connection);
      categoryA = insertCategory(connection, orgA);
      categoryB = insertCategory(connection, orgB);
      insertExpense(connection, orgA, categoryA, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
      candidate = insertExpense(connection, orgB, categoryB, "Acme Corp", 10_000L, "EUR", "INV-1", "2026-07-01");
    }

    assertThat(matcher.findDuplicate(loadExpense(candidate))).isEmpty();
  }

  @Test
  void whenSeveralEarlierEntriesMatchTheMostRecentOneIsChosen() throws SQLException {
    UUID orgId;
    UUID categoryId;
    UUID oldest;
    UUID newest;
    UUID candidate;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      categoryId = insertCategory(connection, orgId);
      oldest =
          insertExpenseWithCreatedAt(
              connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-5", "2026-05-01",
              Instant.now().minusSeconds(3600));
      newest =
          insertExpenseWithCreatedAt(
              connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-5", "2026-06-01",
              Instant.now().minusSeconds(1800));
      candidate = insertExpense(connection, orgId, categoryId, "Acme Corp", 10_000L, "EUR", "INV-5", "2026-07-01");
    }

    Optional<DuplicateMatch> match = matcher.findDuplicate(loadExpense(candidate));

    assertThat(match).isPresent();
    assertThat(match.get().matchedExpenseId()).isEqualTo(newest);
    assertThat(match.get().matchedExpenseId()).isNotEqualTo(oldest);
  }

  private Expense loadExpense(UUID id) {
    return expenseRepository.findById(id).orElseThrow();
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
    return insertExpenseWithCreatedAt(
        connection, orgId, categoryId, vendor, amountMinor, currency, invoiceNumber, issueDate,
        Instant.now());
  }

  private UUID insertExpenseWithCreatedAt(
      Connection connection,
      UUID orgId,
      UUID categoryId,
      String vendor,
      long amountMinor,
      String currency,
      String invoiceNumber,
      String issueDate,
      Instant createdAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO expense (id, organization_id, document_id, category_id, vendor, "
                + "amount_minor, currency, categorization_confidence, status, invoice_number, "
                + "issue_date, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, 'POSTED', ?, ?::date, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setObject(3, insertDocument(connection, orgId));
      ps.setObject(4, categoryId);
      ps.setString(5, vendor);
      ps.setLong(6, amountMinor);
      ps.setString(7, currency);
      ps.setString(8, invoiceNumber);
      ps.setString(9, issueDate);
      ps.setTimestamp(10, Timestamp.from(createdAt));
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
}
