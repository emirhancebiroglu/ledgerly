package com.ledgerly.api.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BudgetResponse(
    UUID id,
    UUID categoryId,
    String period,
    long limitMinor,
    String currency,
    long spentMinor,
    BigDecimal burnRate,
    BudgetStatus status,
    Instant createdAt,
    Instant updatedAt) {

  public static BudgetResponse from(Budget budget, BudgetUsage usage) {
    return new BudgetResponse(
        budget.getId(),
        budget.getCategoryId(),
        budget.getPeriod(),
        budget.getLimitMinor(),
        budget.getCurrency(),
        usage.spentMinor(),
        usage.burnRate(),
        usage.status(),
        budget.getCreatedAt(),
        budget.getUpdatedAt());
  }
}
