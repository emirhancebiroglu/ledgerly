package com.ledgerly.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parses Render's single {@code postgres://user:pass@host:port/db?query} connection string into
 * the JDBC URL plus separate username/password Spring's datasource wants. Kept as a pure
 * function, independent of {@link DatabaseUrlEnvironmentPostProcessor}'s environment mutation, so
 * every edge case here is a plain input/output test.
 */
final class RenderDatabaseUrl {

  private static final int DEFAULT_PORT = 5432;

  private RenderDatabaseUrl() {}

  record Parsed(String jdbcUrl, String username, String password) {}

  /** Returns empty when {@code rawUrl} is not a {@code postgres(ql)://} connection string. */
  static Optional<Parsed> parse(String rawUrl) {
    URI uri;
    try {
      uri = new URI(rawUrl);
    } catch (URISyntaxException e) {
      // No cause chained and no e.getMessage(): both echo the raw value, which may carry
      // credentials in userinfo, straight into a startup stack trace.
      throw new IllegalArgumentException(
          "DATABASE_URL is not a valid URI (" + e.getClass().getSimpleName() + ")");
    }

    if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
      return Optional.empty();
    }

    int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
    String path = uri.getPath() == null ? "" : uri.getPath();
    // Raw (still percent-encoded) query, same reasoning as the raw userinfo below — passing it
    // through as-is rather than through getQuery()'s decode avoids re-encoding it ourselves.
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query;

    // getRawUserInfo() is still percent-encoded; getUserInfo() would already have decoded it,
    // which double-decodes any literal '%' a password contains once we decode again below.
    String userInfo = uri.getRawUserInfo();
    if (userInfo == null || userInfo.isEmpty()) {
      return Optional.of(new Parsed(jdbcUrl, null, null));
    }

    int separator = userInfo.indexOf(':');
    String rawUsername = separator >= 0 ? userInfo.substring(0, separator) : userInfo;
    String rawPassword = separator >= 0 ? userInfo.substring(separator + 1) : "";
    return Optional.of(new Parsed(jdbcUrl, decode(rawUsername), decode(rawPassword)));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
