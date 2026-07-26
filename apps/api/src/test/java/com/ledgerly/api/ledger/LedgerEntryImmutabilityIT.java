package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The application role can INSERT and SELECT on ledger_entry but not UPDATE or DELETE — proven
 * against a real Postgres grant, not by reading the migration.
 */
class LedgerEntryImmutabilityIT extends AbstractPostgresIT {

  @Autowired
  private DataSource dataSource;

  @Test
  void updateAndDeleteAreRejectedForTheApplicationRole() throws Exception {
    UUID entryId;
    try (Connection owner = dataSource.getConnection()) {
      owner.setAutoCommit(false);
      UUID orgId = insertOrganization(owner);
      UUID accountA = insertAccount(owner, orgId, "cash");
      UUID accountB = insertAccount(owner, orgId, "expense");
      UUID txId = insertTransaction(owner, orgId);
      entryId = insertEntry(owner, txId, accountA, "DEBIT", 1_000);
      insertEntry(owner, txId, accountB, "CREDIT", 1_000);
      owner.commit();
    }

    try (Connection appConnection = appRoleConnection()) {
      assertThatThrownBy(() -> {
        try (PreparedStatement ps = appConnection.prepareStatement(
            "UPDATE ledger_entry SET base_amount_minor = 1 WHERE id = ?")) {
          ps.setObject(1, entryId);
          ps.executeUpdate();
        }
      }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");

      assertThatThrownBy(() -> {
        try (PreparedStatement ps = appConnection.prepareStatement(
            "DELETE FROM ledger_entry WHERE id = ?")) {
          ps.setObject(1, entryId);
          ps.executeUpdate();
        }
      }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }
  }

  private Connection appRoleConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), "ledgerly_app", "ledgerly_app_role_fixture");
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

  private UUID insertEntry(
      Connection connection, UUID txId, UUID accountId, String direction, long amountMinor)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO ledger_entry "
            + "(id, transaction_id, account_id, direction, native_amount_minor, "
            + "native_currency, base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, 'EUR', ?, 'EUR', 1)")) {
      ps.setObject(1, id);
      ps.setObject(2, txId);
      ps.setObject(3, accountId);
      ps.setString(4, direction);
      ps.setLong(5, amountMinor);
      ps.setLong(6, amountMinor);
      ps.executeUpdate();
    }
    return id;
  }
}
