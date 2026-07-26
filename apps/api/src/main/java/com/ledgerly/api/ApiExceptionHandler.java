package com.ledgerly.api;

import com.ledgerly.api.auth.CrossOrganizationAccessException;
import com.ledgerly.api.auth.InvalidCredentialsException;
import com.ledgerly.api.auth.InvalidRefreshTokenException;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import com.ledgerly.api.idempotency.IdempotencyConflictException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler({
    NoHandlerFoundException.class,
    NoResourceFoundException.class,
    NoSuchElementException.class,
    CrossOrganizationAccessException.class
  })
  public ProblemDetail handleNotFound() {
    return withCorrelationId(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found"));
  }

  @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
  public ProblemDetail handleUnauthorized(RuntimeException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage()));
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException exception) {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected() {
    return withCorrelationId(
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"));
  }

  private ProblemDetail withCorrelationId(ProblemDetail problemDetail) {
    problemDetail.setProperty("correlationId", CorrelationIdHolder.current());
    return problemDetail;
  }
}
