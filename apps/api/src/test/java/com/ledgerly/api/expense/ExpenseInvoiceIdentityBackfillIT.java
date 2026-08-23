package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V27's backfill UPDATE, re-run against rows inserted to look like pre-migration legacy data
 * (invoice_number/issue_date explicitly NULLed out after insert, as they would have been before
 * this migration ever ran). Re-executes the migration's own SQL file rather than a copy of its
 * UPDATE statement, so a future edit to the migration is exercised by this test without anyone
 * needing to remember to update a duplicate. */
class ExpenseInvoiceIdentityBackfillIT extends AbstractPostgresIT {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void backfillsAReadableProposalAndLeavesAnAbsentOrInvalidOneNull() throws Exception {
    UUID orgId;
    UUID actor;
    UUID categoryId;
    UUID withInvoiceNumberAndDate;
    UUID withNoProposal;
    UUID withMalformedDate;
    UUID withNonObjectProposal;
    try (Connection connection = dataSource.getConnection()) {
      orgId = insertOrganization(connection);
      actor = insertUser(connection, orgId);
      categoryId = insertCategory(connection, orgId);

      withInvoiceNumberAndDate =
          insertExpenseWithDocument(
              connection, orgId, actor, categoryId,
              "{\"invoice_number\": \"  INV-42  \", \"document_date\": \"2026-07-12\"}");
      withNoProposal = insertExpenseWithDocument(connection, orgId, actor, categoryId, null);
      withMalformedDate =
          insertExpenseWithDocument(
              connection, orgId, actor, categoryId,
              // Shape-plausible but not a real calendar date (no month 13) — PostgreSQL's
              // default MDY datestyle would otherwise parse a slash-separated date like
              // "07/12/2026" as a real (if ambiguous) date rather than reject it.
              "{\"invoice_number\": \"INV-7\", \"document_date\": \"2026-13-01\"}");
      withNonObjectProposal = insertExpenseWithDocument(connection, orgId, actor, categoryId, "[]");
    }

    // These columns are populated by the fresh V27 that already ran once for this test class'
    // schema, and by our own inserts above (which left them NULL). Clear them to simulate rows
    // that predate the migration, then re-run the migration's own backfill UPDATE.
    jdbcTemplate.update(
        "UPDATE expense SET invoice_number = NULL, issue_date = NULL WHERE organization_id = ?",
        orgId);
    runBackfillFromMigrationFile();

    assertThat(invoiceNumberOf(withInvoiceNumberAndDate)).isEqualTo("INV-42");
    assertThat(issueDateOf(withInvoiceNumberAndDate)).isEqualTo(LocalDate.of(2026, 7, 12));

    assertThat(invoiceNumberOf(withNoProposal)).isNull();
    assertThat(issueDateOf(withNoProposal)).isNull();

    assertThat(invoiceNumberOf(withMalformedDate)).isEqualTo("INV-7");
    assertThat(issueDateOf(withMalformedDate)).isNull();

    assertThat(invoiceNumberOf(withNonObjectProposal)).isNull();
    assertThat(issueDateOf(withNonObjectProposal)).isNull();
  }

  /** Extracts and re-runs only the migration's backfill UPDATE (and its helper function), not the
   * ADD COLUMN/CREATE INDEX statements that already applied when this test class' schema was
   * migrated once at container start. */
  private void runBackfillFromMigrationFile() throws Exception {
    Path migration =
        Path.of(
            "src/main/resources/db/migration/V27__expense_invoice_identity.sql");
    String sql = Files.readString(migration);
    int functionStart = sql.indexOf("CREATE FUNCTION");
    int dropEnd = sql.indexOf("DROP FUNCTION expense_invoice_identity_safe_to_date(TEXT);")
        + "DROP FUNCTION expense_invoice_identity_safe_to_date(TEXT);".length();
    String backfillOnly = sql.substring(functionStart, dropEnd);
    try (Connection connection = dataSource.getConnection();
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(backfillOnly);
    }
  }

  private String invoiceNumberOf(UUID expenseId) {
    return jdbcTemplate.queryForObject(
        "SELECT invoice_number FROM expense WHERE id = ?", String.class, expenseId);
  }

  private LocalDate issueDateOf(UUID expenseId) {
    return jdbcTemplate.queryForObject(
        "SELECT issue_date FROM expense WHERE id = ?", LocalDate.class, expenseId);
  }

  private UUID insertExpenseWithDocument(
      Connection connection, UUID orgId, UUID actor, UUID categoryId, String proposalJson)
      throws SQLException {
    UUID documentId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
                + "size_bytes, storage_key, content_hash, status, proposal) "
                + "VALUES (?, ?, ?, ?, 'application/pdf', 1, ?, ?, 'EXTRACTED', ?::jsonb)")) {
      ps.setObject(1, documentId);
      ps.setObject(2, orgId);
      ps.setObject(3, actor);
      ps.setString(4, "doc-" + documentId + ".pdf");
      ps.setString(5, "doc-" + documentId);
      ps.setString(6, "hash-" + documentId);
      ps.setString(7, proposalJson);
      ps.executeUpdate();
    }
    UUID expenseId = UUID.randomUUID();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO expense (id, organization_id, document_id, category_id, amount_minor, "
                + "currency, categorization_confidence, status) "
                + "VALUES (?, ?, ?, ?, 1000, 'EUR', 0.9, 'POSTED')")) {
      ps.setObject(1, expenseId);
      ps.setObject(2, orgId);
      ps.setObject(3, documentId);
      ps.setObject(4, categoryId);
      ps.executeUpdate();
    }
    return expenseId;
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
