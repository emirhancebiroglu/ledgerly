package com.ledgerly.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ProblemDetail handleNotFound() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
  }
}
