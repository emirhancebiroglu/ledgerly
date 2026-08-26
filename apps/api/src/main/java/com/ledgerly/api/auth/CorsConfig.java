package com.ledgerly.api.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for the application's own API, distinct from {@code management.endpoints.web.cors} in
 * application.yml (actuator's own, separate CORS config). `web`'s server-side route handlers
 * and Server Actions never hit this — same-origin from the browser's point of view — but a few
 * pages fetch `api`/`ai` directly from the browser (see {@code /health}), and any future
 * browser-side integration needs this wired rather than discovered missing at deploy time.
 */
@Configuration
public class CorsConfig {

  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${ledgerly.cors.allowed-origins:http://localhost:3000}") String allowedOriginsProperty) {
    List<String> allowedOrigins =
        Arrays.stream(allowedOriginsProperty.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();

    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }
}
