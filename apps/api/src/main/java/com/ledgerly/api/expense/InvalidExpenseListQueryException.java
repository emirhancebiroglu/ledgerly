package com.ledgerly.api.expense;

/** {@code GET /api/v1/expenses} was called with an unknown status, sort field, or direction. */
public class InvalidExpenseListQueryException extends RuntimeException {

  public InvalidExpenseListQueryException(String message) {
    super(message);
  }
}
