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
  void isRegisteredInTheImportsFileSpringBootActuallyReads() throws java.io.IOException {
    // Guards against registering in the wrong file (legacy spring.factories is silently
    // ignored for EnvironmentPostProcessor on Boot 3.x) — a hand-called unit test below would
    // stay green even if the processor never actually runs in the real application.
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports")) {
      assertThat(stream).as("imports file must exist on the classpath").isNotNull();
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

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://dpg-abc123:5432/ledgerly_prod");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("ledgerly");
    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("s3cret");
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

    assertThat(environment.getProperty("spring.datasource.url")).isNull();
  }
}
