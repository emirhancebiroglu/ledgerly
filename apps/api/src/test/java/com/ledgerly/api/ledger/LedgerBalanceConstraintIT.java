package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the balance invariant is enforced by the database itself, independently of the
 * domain — raw SQL, bypassing {@link LedgerTransaction} entirely, and genuinely deferred to
 * COMMIT rather than checked at INSERT time.
 */
class LedgerBalanceConstraintIT extends AbstractPostgresIT {

  @Autowired
  private DataSource dataSource;

  @Test
  void balancedTransactionCommits() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID orgId = insertOrganization(connection);
      UUID accountA = insertAccount(connection, orgId, "cash");
      UUID accountB = insertAccount(connection, orgId, "expense");
      UUID txId = insertTransaction(connection, orgId);

      insertEntry(connection, txId, accountA, "DEBIT", 1_000);
      insertEntry(connection, txId, accountB, "CREDIT", 1_000);

      connection.commit();
    }
  }

  @Test
  void unbalancedInsertRaisesAtCommitNotAtInsert() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID orgId = insertOrganization(connection);
      UUID accountA = insertAccount(connection, orgId, "cash");
      UUID accountB = insertAccount(connection, orgId, "expense");
      UUID txId = insertTransaction(connection, orgId);

      // both inserts must succeed individually — the constraint is deferred
      insertEntry(connection, txId, accountA, "DEBIT", 1_000);
      insertEntry(connection, txId, accountB, "CREDIT", 900);

      assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
      connection.rollback();
    }
  }

  @Test
  void transientlyUnbalancedMidTransactionButBalancedAtCommitSucceeds() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID orgId = insertOrganization(connection);
      UUID accountA = insertAccount(connection, orgId, "cash");
      UUID accountB = insertAccount(connection, orgId, "expense");
      UUID accountC = insertAccount(connection, orgId, "tax");
      UUID txId = insertTransaction(connection, orgId);

      // after this single insert the transaction is unbalanced — no exception here,
      // proving the check does not run at INSERT time
      insertEntry(connection, txId, accountA, "DEBIT", 1_000);
      insertEntry(connection, txId, accountB, "CREDIT", 700);
      insertEntry(connection, txId, accountC, "CREDIT", 300);

      connection.commit();
    }
  }

  private UUID insertOrganization(Connection connection) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO organization (id, name, base_currency) VALUES (?, ?, 'EUR')")) {
      ps.setObject(1, id);
      ps.setString(2, "org-" + id);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertAccount(Connection connection, UUID orgId, String name) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO account (id, organization_id, name, account_type, currency) "
            + "VALUES (?, ?, ?, 'ASSET', 'EUR')")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.setString(3, name + "-" + id);
      ps.executeUpdate();
    }
    return id;
  }

  private UUID insertTransaction(Connection connection, UUID orgId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at) "
            + "VALUES (?, ?, 'EUR', now())")) {
      ps.setObject(1, id);
      ps.setObject(2, orgId);
      ps.executeUpdate();
    }
    return id;
  }

  private void insertEntry(
      Connection connection, UUID txId, UUID accountId, String direction, long amountMinor)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO ledger_entry "
            + "(id, transaction_id, account_id, direction, native_amount_minor, "
            + "native_currency, base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, 'EUR', ?, 'EUR', 1)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, txId);
      ps.setObject(3, accountId);
      ps.setString(4, direction);
      ps.setLong(5, amountMinor);
      ps.setLong(6, amountMinor);
      ps.executeUpdate();
    }
  }
}
