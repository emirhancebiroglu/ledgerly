package com.ledgerly.api.expense;

import java.util.UUID;

/** An approve/correct action targeted an expense that is no longer {@code NEEDS_REVIEW}. */
public class ExpenseAlreadyResolvedException extends RuntimeException {

  public ExpenseAlreadyResolvedException(UUID expenseId) {
    super("Expense is not awaiting review: " + expenseId);
  }
}
