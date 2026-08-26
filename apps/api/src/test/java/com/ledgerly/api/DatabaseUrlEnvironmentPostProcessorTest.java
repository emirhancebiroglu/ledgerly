package com.ledgerly.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

  private final DatabaseUrlEnvironmentPostProcessor postProcessor =
      new DatabaseUrlEnvironmentPostProcessor();

  @Test
  void isRegisteredInSpringFactoriesSpringBootActuallyReads() throws java.io.IOException {
    // EnvironmentPostProcessor is discovered via spring.factories, not the newer
    // META-INF/spring/*.imports mechanism (that one is for AutoConfiguration) — registering it
    // in the wrong file leaves the class silently never instantiated, so the fat jar boots with
    // no error but DATABASE_URL is never bridged. A hand-called unit test below would stay
    // green even in that state, which is exactly what happened here (caught only by a real
    // fat-jar boot against a postgres:// DATABASE_URL, not by this classpath check alone).
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
      assertThat(stream).as("spring.factories must exist on the classpath").isNotNull();
      String contents = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(contents).contains(DatabaseUrlEnvironmentPostProcessor.class.getName());
    }
  }

  @Test
  void bridgesRenderConnectionStringIntoSpringDatasourceProperties() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                java.util.Map.of(
                    "DATABASE_URL", "postgres://ledgerly:s3cret@dpg-abc123:5432/ledgerly_prod")));

    postProcessor.postProcessEnvironment(environment, new SpringApplication());

    // Rewrites DATABASE_URL/POSTGRES_USER/POSTGRES_PASSWORD themselves, not
    // spring.datasource.*: application.yml's own spring.datasource.url:
    // ${DATABASE_URL:...} placeholder is what actually reads these back.
    assertThat(environment.getProperty("DATABASE_URL"))
        .isEqualTo("jdbc:postgresql://dpg-abc123:5432/ledgerly_prod");
    assertThat(environment.getProperty("POSTGRES_USER")).isEqualTo("ledgerly");
    assertThat(environment.getProperty("POSTGRES_PASSWORD")).isEqualTo("s3cret");
  }

  @Test
  void leavesAlreadyJdbcUrlUntouched() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test", java.util.Map.of("DATABASE_URL", "jdbc:postgresql://localhost:5432/ledgerly")));

    postProcessor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getPropertySources().contains("databaseUrlBridge")).isFalse();
  }

  @Test
  void doesNothingWhenDatabaseUrlIsAbsent() {
    var environment = new StandardEnvironment();

    postProcessor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getPropertySources().contains("databaseUrlBridge")).isFalse();
  }
}
