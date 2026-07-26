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
}
