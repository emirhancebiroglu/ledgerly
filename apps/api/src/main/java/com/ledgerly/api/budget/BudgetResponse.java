package com.ledgerly.api.budget;

import java.time.Instant;
import java.util.UUID;

public record BudgetResponse(
    UUID id,
    UUID categoryId,
    String period,
    long limitMinor,
    String currency,
    Instant createdAt,
    Instant updatedAt) {

  public static BudgetResponse from(Budget budget) {
    return new BudgetResponse(
        budget.getId(),
        budget.getCategoryId(),
        budget.getPeriod(),
        budget.getLimitMinor(),
        budget.getCurrency(),
        budget.getCreatedAt(),
        budget.getUpdatedAt());
  }
}
