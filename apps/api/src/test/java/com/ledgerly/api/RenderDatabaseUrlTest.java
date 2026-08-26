package com.ledgerly.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RenderDatabaseUrlTest {

  @Test
  void bridgesConnectionStringToJdbcUrlAndCredentials() {
    var parsed = RenderDatabaseUrl.parse("postgres://ledgerly:s3cret@dpg-abc123:5432/ledgerly_prod").orElseThrow();

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123:5432/ledgerly_prod");
    assertThat(parsed.username()).isEqualTo("ledgerly");
    assertThat(parsed.password()).isEqualTo("s3cret");
  }

  @Test
  void decodesPercentEncodedSpecialCharactersInCredentialsExactlyOnce() {
    // A literal '%' in a password only survives round-tripping if decoding runs once against
    // the *raw* (still percent-encoded) userinfo — decoding twice corrupts it.
    var parsed = RenderDatabaseUrl.parse("postgres://us%40er:p%40ss%3Aword%25tag@dpg-abc123:5432/db").orElseThrow();

    assertThat(parsed.username()).isEqualTo("us@er");
    assertThat(parsed.password()).isEqualTo("p@ss:word%tag");
  }

  @Test
  void preservesQueryStringSuchAsSslmode() {
    var parsed = RenderDatabaseUrl.parse("postgres://ledgerly:pw@dpg-abc123:5432/db?sslmode=require").orElseThrow();

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123:5432/db?sslmode=require");
  }

  @Test
  void preservesQueryStringRawRatherThanDecodingIt() {
    // getRawQuery() rather than getQuery(): a percent-encoded query value must reach the JDBC
    // URL exactly as given, not decoded then left un-re-encoded.
    var parsed =
        RenderDatabaseUrl.parse("postgres://ledgerly:pw@dpg-abc123:5432/db?options=-c%20search_path%3Dpublic")
            .orElseThrow();

    assertThat(parsed.jdbcUrl())
        .isEqualTo("jdbc:postgresql://dpg-abc123:5432/db?options=-c%20search_path%3Dpublic");
  }

  @Test
  void defaultsToPort5432WhenPortIsOmitted() {
    var parsed = RenderDatabaseUrl.parse("postgres://ledgerly:pw@dpg-abc123/db").orElseThrow();

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123:5432/db");
  }

  @Test
  void handlesMissingPathComponent() {
    var parsed = RenderDatabaseUrl.parse("postgres://ledgerly:pw@dpg-abc123:5432").orElseThrow();

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123:5432");
  }

  @Test
  void handlesIpv6Host() {
    var parsed = RenderDatabaseUrl.parse("postgres://ledgerly:pw@[::1]:5432/db").orElseThrow();

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://[::1]:5432/db");
  }

  @Test
  void returnsEmptyForNonPostgresScheme() {
    assertThat(RenderDatabaseUrl.parse("jdbc:postgresql://localhost:5432/ledgerly")).isEmpty();
    assertThat(RenderDatabaseUrl.parse("mysql://user:pw@host:3306/db")).isEmpty();
  }

  @Test
  void returnsEmptyCredentialsWhenUserInfoIsAbsent() {
    var parsed = RenderDatabaseUrl.parse("postgres://dpg-abc123:5432/db").orElseThrow();

    assertThat(parsed.username()).isNull();
    assertThat(parsed.password()).isNull();
  }

  @Test
  void failsFastOnMalformedUrlWithoutEchoingItsCredentials() {
    // A raw, unencoded '%' (not a valid percent-escape) makes the value an invalid URI. The
    // cause must not be chained either — java.net.URISyntaxException's own message embeds the
    // full input string, so chaining it as a cause would leak the password into any logged
    // stack trace even though the outer message is clean.
    assertThatThrownBy(() -> RenderDatabaseUrl.parse("postgres://user:p%ss@host:5432/db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("p%ss")
        .hasNoCause();
  }
}
