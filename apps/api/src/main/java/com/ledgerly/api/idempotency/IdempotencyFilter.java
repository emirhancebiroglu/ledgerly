package com.ledgerly.api.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper;

  public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
    this.idempotencyService = idempotencyService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    Object principalCandidate =
        SecurityContextHolder.getContext().getAuthentication() != null
            ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            : null;
    if (!MUTATING_METHODS.contains(request.getMethod())
        || !(principalCandidate instanceof AuthenticatedPrincipal principal)) {
      // Unauthenticated or non-mutating requests (e.g. register/login) don't carry an org to
      // scope the idempotency key to, and aren't the endpoints this filter protects.
      filterChain.doFilter(request, response);
      return;
    }

    String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
    if (key == null || key.isBlank()) {
      response.sendError(HttpStatus.BAD_REQUEST.value(), "Idempotency-Key header is required");
      return;
    }

    CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);
    String requestHash = hash(wrappedRequest.getBody());
    String endpoint = request.getRequestURI();

    IdempotencyService.ClaimResult claimResult;
    try {
      claimResult =
          idempotencyService.claimOrReplay(principal.organizationId(), key, endpoint, requestHash);
    } catch (IdempotencyConflictException conflict) {
      writeProblemDetail(response, HttpStatus.CONFLICT, conflict.getMessage());
      return;
    }

    if (claimResult instanceof IdempotencyService.Replay(int status, String body)) {
      response.setStatus(status);
      response.setContentType("application/json");
      response.getWriter().write(body);
      return;
    }

    UUID recordId = ((IdempotencyService.Claimed) claimResult).recordId();
    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(wrappedRequest, wrappedResponse);
    } finally {
      String body =
          new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
      idempotencyService.complete(recordId, wrappedResponse.getStatus(), body);
      wrappedResponse.copyBodyToResponse();
    }
  }

  private void writeProblemDetail(HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/problem+json");
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    response.getWriter().write(objectMapper.writeValueAsString(problemDetail));
  }

  private String hash(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Base64.getEncoder().encodeToString(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
