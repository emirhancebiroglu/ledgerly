package com.ledgerly.api.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseResponse(
    UUID id,
    UUID documentId,
    String vendor,
    UUID categoryId,
    UUID ledgerTransactionId,
    long amountMinor,
    String currency,
    double categorizationConfidence,
    String citation,
    ExpenseStatus status,
    Instant createdAt) {

  public static ExpenseResponse from(Expense expense) {
    return new ExpenseResponse(
        expense.getId(),
        expense.getDocumentId(),
        expense.getVendor(),
        expense.getCategoryId(),
        expense.getLedgerTransactionId(),
        expense.getAmountMinor(),
        expense.getCurrency(),
        expense.getCategorizationConfidence(),
        expense.getCitation(),
        expense.getStatus(),
        expense.getCreatedAt());
  }
}
