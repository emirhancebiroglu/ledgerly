package com.ledgerly.api.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper;
  private final MultipartResolver multipartResolver;

  public IdempotencyFilter(
      IdempotencyService idempotencyService,
      ObjectMapper objectMapper,
      MultipartResolver multipartResolver) {
    this.idempotencyService = idempotencyService;
    this.objectMapper = objectMapper;
    this.multipartResolver = multipartResolver;
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

    // Multipart is handled separately and deliberately. Spring's multipart resolver consumes the
    // raw stream via getParts(), so wrapping and calling getInputStream() here yields ZERO bytes —
    // every multipart request would hash identically, and a replayed key carrying a completely
    // different file would be served the first request's response instead of a 409.
    HttpServletRequest requestToForward;
    String requestHash;
    MultipartHttpServletRequest alreadyResolved = findResolvedMultipart(request);
    if (alreadyResolved != null) {
      // Forward the request untouched. The parts are already parsed somewhere in the wrapper
      // chain; resolving again would build a fresh wrapper with none of them, and the handler
      // would see 'file' as missing.
      requestToForward = request;
      requestHash = hashMultipart(alreadyResolved);
    } else if (isMultipart(request)) {
      MultipartHttpServletRequest resolved = multipartResolver.resolveMultipart(request);
      requestToForward = resolved;
      requestHash = hashMultipart(resolved);
    } else {
      CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);
      requestToForward = wrapped;
      requestHash = hash(wrapped.getBody());
    }
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
      filterChain.doFilter(requestToForward, wrappedResponse);
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

  /**
   * Finds an already-parsed multipart request anywhere in the wrapper chain.
   *
   * <p>By the time this filter runs, the request has usually been wrapped — Spring Security alone
   * adds several layers. A plain {@code instanceof} on the outermost object therefore misses a
   * multipart request that has in fact already been resolved, and re-resolving it would produce a
   * wrapper holding no parts at all.
   */
  private MultipartHttpServletRequest findResolvedMultipart(ServletRequest request) {
    ServletRequest current = request;
    while (current != null) {
      if (current instanceof MultipartHttpServletRequest multipart) {
        return multipart;
      }
      if (current instanceof ServletRequestWrapper wrapper) {
        current = wrapper.getRequest();
      } else {
        return null;
      }
    }
    return null;
  }

  private boolean isMultipart(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
  }

  /**
   * Fingerprints a multipart request from its resolved files and form fields.
   *
   * <p>The raw stream is unusable here: by the time this filter runs the multipart resolver has
   * already consumed it, so {@code getInputStream()} yields zero bytes and every upload would hash
   * alike. Resolving through Spring's {@link MultipartResolver} instead reads the parts the handler
   * itself will see, and leaves them intact for it.
   *
   * <p>File name, field name and full content all feed the digest, so two uploads differing in any
   * of them are distinguishable — which is what makes a replay carrying a *different* document a
   * 409 rather than a silent hand-back of the first document's response.
   */
  private String hashMultipart(MultipartHttpServletRequest multipartRequest) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      // Sorted: part order is not guaranteed across clients, so the same upload must hash alike.
      List<String> fileNames = new ArrayList<>(multipartRequest.getFileMap().keySet());
      fileNames.sort(Comparator.naturalOrder());
      for (String name : fileNames) {
        MultipartFile file = multipartRequest.getFileMap().get(name);
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
          digest.update(originalFilename.getBytes(StandardCharsets.UTF_8));
        }
        // getBytes(), not getInputStream(): the stream can only be consumed once, and draining it
        // here would leave the handler with no part at all.
        digest.update(file.getBytes());
      }

      List<String> parameterNames = new ArrayList<>(multipartRequest.getParameterMap().keySet());
      parameterNames.sort(Comparator.naturalOrder());
      for (String name : parameterNames) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        for (String value : multipartRequest.getParameterMap().get(name)) {
          digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
      }

      return Base64.getEncoder().encodeToString(digest.digest());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to fingerprint multipart request", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
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
