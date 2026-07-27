package com.ledgerly.api.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates (or propagates a client-supplied) correlation id at the very edge of every request,
 * before auth or any other filter runs, so it is available to every log line and to the
 * response. Ordered explicitly ahead of {@code SecurityProperties.DEFAULT_FILTER_ORDER} — the
 * order Spring Boot assigns the whole {@code springSecurityFilterChain} — rather than relying on
 * {@code Ordered.HIGHEST_PRECEDENCE} happening to sit before it by coincidence.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  private static final int MAX_LENGTH = 128;

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = sanitize(request.getHeader(HEADER));
    String correlationId = (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();

    MDC.put(CorrelationIdHolder.MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      log.info("{} {}", request.getMethod(), request.getRequestURI());
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(CorrelationIdHolder.MDC_KEY);
    }
  }

  /**
   * A client-supplied correlation id flows unsanitized into log lines and a response header;
   * strip control characters (CR/LF log-injection, header-splitting) and cap length before
   * trusting it.
   */
  private String sanitize(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.replaceAll("[\\p{Cntrl}]", "");
    return stripped.length() > MAX_LENGTH ? stripped.substring(0, MAX_LENGTH) : stripped;
  }
}
