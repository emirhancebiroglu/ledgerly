package com.ledgerly.api.ledger;

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
abstract class AbstractPostgresIT {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
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
}
