package com.ledgerly.api.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container pattern: one Postgres container for the whole JVM, started once and never
 * stopped by JUnit — Testcontainers' Ryuk sidecar reaps it at JVM exit. Letting {@code
 * @Testcontainers}/{@code @Container} manage the lifecycle instead starts and stops a fresh
 * container per test class, which is both slow and, on this machine, prone to connection
 * timeouts under the resulting churn.
 */
@Tag("integration")
@SpringBootTest
public abstract class AbstractPostgresIT {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ledgerly_test")
          .withUsername("ledgerly")
          .withPassword("ledgerly");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  protected UUID insertOrganization(Connection connection) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO organization (id, name, base_currency) VALUES (?, ?, 'EUR')")) {
      ps.setObject(1, id);
      ps.setString(2, "org-" + id);
      ps.executeUpdate();
    }
    return id;
  }

  UUID insertAccount(Connection connection, UUID orgId, String name) throws SQLException {
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

  UUID insertTransaction(Connection connection, UUID orgId) throws SQLException {
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

  UUID insertEntry(Connection connection, UUID txId, UUID accountId, String direction, long amountMinor)
      throws SQLException {
    return insertEntry(connection, txId, accountId, direction, amountMinor, "EUR");
  }

  UUID insertEntry(
      Connection connection,
      UUID txId,
      UUID accountId,
      String direction,
      long amountMinor,
      String baseCurrency)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO ledger_entry "
            + "(id, transaction_id, account_id, direction, native_amount_minor, "
            + "native_currency, base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)")) {
      ps.setObject(1, id);
      ps.setObject(2, txId);
      ps.setObject(3, accountId);
      ps.setString(4, direction);
      ps.setLong(5, amountMinor);
      ps.setString(6, baseCurrency);
      ps.setLong(7, amountMinor);
      ps.setString(8, baseCurrency);
      ps.executeUpdate();
    }
    return id;
  }
}
