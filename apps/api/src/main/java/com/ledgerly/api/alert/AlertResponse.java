package com.ledgerly.api.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID id, UUID expenseId, UUID categoryId, String period, String currency,
    String alertType, Integer thresholdPercent, Long spentMinor, Long limitMinor, Integer historyCount,
    BigDecimal zScore, BigDecimal budgetBurnRate, String explanation, String model, Instant createdAt) {
  public static AlertResponse from(Alert alert) {
    return new AlertResponse(alert.getId(), alert.getExpenseId(), alert.getCategoryId(), alert.getPeriod(),
        alert.getCurrency(), alert.getAlertType(), alert.getThresholdPercent(), alert.getSpentMinor(),
        alert.getLimitMinor(), alert.getHistoryCount(), alert.getZScore(), alert.getBudgetBurnRate(),
        alert.getExplanation(), alert.getModel(), alert.getCreatedAt());
  }
}
