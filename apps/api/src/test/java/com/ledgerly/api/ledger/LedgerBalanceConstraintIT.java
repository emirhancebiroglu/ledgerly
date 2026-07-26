package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
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
  void crossCurrencyEntriesThatNetToZeroAreRejected() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID orgId = insertOrganization(connection);
      UUID accountA = insertAccount(connection, orgId, "cash");
      UUID accountB = insertAccount(connection, orgId, "expense");
      UUID txId = insertTransaction(connection, orgId);

      // net base amount is zero, but the transaction's base_currency is EUR and this
      // entry claims USD — must still be rejected
      insertEntry(connection, txId, accountA, "DEBIT", 1_000, "EUR");
      insertEntry(connection, txId, accountB, "CREDIT", 1_000, "USD");

      assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
      connection.rollback();
    }
  }

  @Test
  void transactionWithNoEntriesIsRejectedAtCommit() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID orgId = insertOrganization(connection);
      insertTransaction(connection, orgId);

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
}
