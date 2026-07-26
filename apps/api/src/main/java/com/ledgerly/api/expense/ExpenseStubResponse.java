package com.ledgerly.api.expense;

import java.util.UUID;

public record ExpenseStubResponse(UUID id, long amountMinor, String currency) {

  static ExpenseStubResponse from(ExpenseStub expense) {
    return new ExpenseStubResponse(expense.getId(), expense.getAmountMinor(), expense.getCurrency());
  }
}
