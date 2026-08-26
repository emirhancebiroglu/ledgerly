package com.ledgerly.api;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Render's managed Postgres hands out a single {@code postgres://user:pass@host:port/db}
 * connection string. Spring's datasource wants a JDBC URL and separate username/password
 * properties. This runs before context refresh, so by the time {@code application.yml}
 * resolves {@code ${DATABASE_URL}} the bridged properties already win by precedence.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String SOURCE_NAME = "databaseUrlBridge";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String rawUrl = environment.getProperty("DATABASE_URL");
    if (rawUrl == null || rawUrl.isBlank() || rawUrl.startsWith("jdbc:")) {
      return;
    }

    RenderDatabaseUrl.parse(rawUrl)
        .ifPresent(
            parsed -> {
              Map<String, Object> properties = new HashMap<>();
              properties.put("spring.datasource.url", parsed.jdbcUrl());
              if (parsed.username() != null) {
                properties.put("spring.datasource.username", parsed.username());
                properties.put("spring.datasource.password", parsed.password());
              }

              MutablePropertySources sources = environment.getPropertySources();
              sources.addFirst(new MapPropertySource(SOURCE_NAME, properties));
            });
  }
}
