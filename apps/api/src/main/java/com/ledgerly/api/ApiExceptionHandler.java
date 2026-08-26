package com.ledgerly.api;

import com.ledgerly.api.alert.InvalidAlertTypeException;
import com.ledgerly.api.auth.CrossOrganizationAccessException;
import com.ledgerly.api.auth.InvalidCredentialsException;
import com.ledgerly.api.auth.InvalidRefreshTokenException;
import com.ledgerly.api.budget.DuplicateBudgetException;
import com.ledgerly.api.budget.InvalidBudgetRequestException;
import com.ledgerly.api.category.CategoryInUseException;
import com.ledgerly.api.category.DuplicateCategoryNameException;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import com.ledgerly.api.document.DocumentTooLargeException;
import com.ledgerly.api.document.IllegalDocumentTransitionException;
import com.ledgerly.api.document.UnsupportedDocumentTypeException;
import com.ledgerly.api.expense.ExpenseAlreadyResolvedException;
import com.ledgerly.api.expense.ExpenseCategoryRequiredException;
import com.ledgerly.api.expense.InvalidExpenseListQueryException;
import com.ledgerly.api.idempotency.IdempotencyConflictException;
import com.ledgerly.api.policy.IllegalPolicyDocumentTransitionException;
import com.ledgerly.api.policy.InvalidPolicyListQueryException;
import com.ledgerly.api.ratelimit.RateLimitExceededException;
import com.ledgerly.api.ratelimit.RateLimitUnavailableException;
import com.ledgerly.api.storage.StorageKeyNotFoundException;
import io.sentry.Sentry;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({
    NoHandlerFoundException.class,
    NoResourceFoundException.class,
    NoSuchElementException.class,
    CrossOrganizationAccessException.class,
    StorageKeyNotFoundException.class
  })
  public ProblemDetail handleNotFound() {
    return withCorrelationId(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found"));
  }

  @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
  public ProblemDetail handleUnauthorized(RuntimeException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage()));
  }

  @ExceptionHandler({ExpenseAlreadyResolvedException.class, ExpenseCategoryRequiredException.class})
  public ProblemDetail handleExpenseResolutionConflict(RuntimeException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(InvalidExpenseListQueryException.class)
  public ProblemDetail handleInvalidExpenseListQuery(InvalidExpenseListQueryException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
  }

  @ExceptionHandler(InvalidPolicyListQueryException.class)
  public ProblemDetail handleInvalidPolicyListQuery(InvalidPolicyListQueryException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
  }

  @ExceptionHandler(InvalidAlertTypeException.class)
  public ProblemDetail handleInvalidAlertType(InvalidAlertTypeException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(DuplicateCategoryNameException.class)
  public ProblemDetail handleDuplicateCategoryName(DuplicateCategoryNameException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(DuplicateBudgetException.class)
  public ProblemDetail handleDuplicateBudget(DuplicateBudgetException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(InvalidBudgetRequestException.class)
  public ProblemDetail handleInvalidBudgetRequest(InvalidBudgetRequestException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleInvalidRequest(MethodArgumentNotValidException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed"));
  }

  @ExceptionHandler(CategoryInUseException.class)
  public ProblemDetail handleCategoryInUse(CategoryInUseException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(UnsupportedDocumentTypeException.class)
  public ProblemDetail handleUnsupportedDocumentType(UnsupportedDocumentTypeException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getMessage()));
  }

  @ExceptionHandler({DocumentTooLargeException.class, MaxUploadSizeExceededException.class})
  public ProblemDetail handleDocumentTooLarge() {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds the maximum accepted size"));
  }

  @ExceptionHandler(IllegalDocumentTransitionException.class)
  public ProblemDetail handleIllegalDocumentTransition(
      IllegalDocumentTransitionException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(IllegalPolicyDocumentTransitionException.class)
  public ProblemDetail handleIllegalPolicyDocumentTransition(
      IllegalPolicyDocumentTransitionException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException exception) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
        .body(withCorrelationId(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded")));
  }

  @ExceptionHandler(RateLimitUnavailableException.class)
  public ProblemDetail handleRateLimitUnavailable() {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable"));
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception exception) {
    // Logged, never returned: the client gets a correlation id to quote, and the detail stays
    // server-side where it cannot leak internals.
    log.error("Unhandled exception type={} status=500", exception.getClass().getSimpleName());
    // This @RestControllerAdvice catches every exception before it can reach the servlet
    // container, which is exactly the layer Sentry's Spring auto-instrumentation listens at —
    // without this call, nothing an application exception handler catches (i.e. everything)
    // would ever reach Sentry. A no-op when SENTRY_DSN is unset (SDK not initialized).
    Sentry.captureException(exception);
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"));
  }

  private ProblemDetail withCorrelationId(ProblemDetail problemDetail) {
    problemDetail.setProperty("correlationId", CorrelationIdHolder.current());
    return problemDetail;
  }
}
