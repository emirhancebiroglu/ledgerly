package com.ledgerly.api.ledger;

import com.redis.testcontainers.RedisContainer;
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
 * Singleton-container pattern: one Postgres (and, since M7a T6, one Redis) container for the
 * whole JVM, started once and never stopped by JUnit — Testcontainers' Ryuk sidecar reaps them at
 * JVM exit. Letting {@code @Testcontainers}/{@code @Container} manage the lifecycle instead
 * starts and stops a fresh container per test class, which is both slow and, on this machine,
 * prone to connection timeouts under the resulting churn.
 */
@Tag("integration")
@SpringBootTest(
    properties = {
      // DocumentQueuePoller's fixedDelay fires once at startup regardless of the interval, and
      // every IT context shares this one Postgres container with no cleanup between them — a
      // Failsafe fork can hold several contexts alive at once under Spring's test context cache,
      // each with its own scheduled poller thread still ticking. A poller that finds another
      // context's PENDING document and claims it turns that document PROCESSING out from under
      // whichever test created it, which reads as a flaky "expected PENDING but was PROCESSING"
      // in a class that never touched the poller. Off by default: a test exercising the poller
      // itself (DocumentStatusPipelineIT, ExpensePostingPipelineIT) overrides both properties
      // explicitly via its own @TestPropertySource, which takes precedence over this default.
      "ledgerly.document.queue.interval-seconds=3600",
      "ledgerly.document.queue.initial-delay-seconds=3600"
    })
public abstract class AbstractPostgresIT {

  // Public so a context deliberately opting out of this class's Redis wiring (M9.9 T6's
  // NoRedisBootIT) can still share the one Postgres container per JVM instead of starting a
  // second, redundant one of its own.
  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ledgerly_test")
          .withUsername("ledgerly")
          .withPassword("ledgerly")
          // Default max_connections (100) is exhausted once enough IT classes force a distinct
          // Spring context (each opens its own Hikari pool) in one `mvn verify` run — surfaces as
          // "sorry, too many clients already" rather than a real test defect. Raised well past
          // the observed peak; this is a shared JVM-wide container, not per-test state.
          .withCommand("postgres", "-c", "max_connections=300");

  static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
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
