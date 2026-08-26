package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CorsConfigParsingTest {

  private final CorsConfig corsConfig = new CorsConfig();

  @Test
  void splitsAndTrimsACommaSeparatedOriginList() {
    var source =
        corsConfig.corsConfigurationSource(" https://a.example.com ,https://b.example.com");

    var configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/auth/login"));

    assertThat(configuration.getAllowedOrigins())
        .containsExactly("https://a.example.com", "https://b.example.com");
  }

  @Test
  void ignoresEmptyEntriesFromATrailingComma() {
    var source = corsConfig.corsConfigurationSource("https://a.example.com,");

    var configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/auth/login"));

    assertThat(configuration.getAllowedOrigins()).containsExactly("https://a.example.com");
  }

  @Test
  void doesNotRegisterAConfigurationOutsideTheApiPathPrefix() {
    var source = corsConfig.corsConfigurationSource("https://a.example.com");

    var configuration =
        source.getCorsConfiguration(new MockHttpServletRequest("GET", "/actuator/health"));

    assertThat(configuration).isNull();
  }
}
