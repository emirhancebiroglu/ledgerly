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
 * properties, and {@code application.yml} gets there by resolving {@code ${DATABASE_URL}} /
 * {@code ${POSTGRES_USER}} / {@code ${POSTGRES_PASSWORD}} placeholders itself — so this
 * rewrites those three keys in place rather than writing a separate {@code spring.datasource.*}
 * property source. Writing separate keys was tried first and lost the precedence race: Boot's
 * own {@code ConfigDataEnvironmentPostProcessor} loads {@code application.yml} and adds its
 * property source at the front of the list too, after this post-processor already ran, so
 * application.yml's {@code spring.datasource.url: ${DATABASE_URL:...}} placeholder resolution
 * saw this class's addition and application.yml's own definition as two competing
 * spring.datasource.url values — and on Render, application.yml's (still unresolved, so still
 * the raw postgres:// string) won. Rewriting DATABASE_URL/POSTGRES_USER/POSTGRES_PASSWORD
 * instead means there is only ever one definition of each key, so there is nothing left to race.
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
              properties.put("DATABASE_URL", parsed.jdbcUrl());
              if (parsed.username() != null) {
                properties.put("POSTGRES_USER", parsed.username());
                properties.put("POSTGRES_PASSWORD", parsed.password());
              }

              MutablePropertySources sources = environment.getPropertySources();
              sources.addFirst(new MapPropertySource(SOURCE_NAME, properties));
            });
  }
}
