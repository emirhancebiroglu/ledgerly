package com.ledgerly.api.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record AlertResponse(UUID id, UUID expenseId, UUID categoryId, String period, String currency,
    String alertType, Integer thresholdPercent,
    @JsonSerialize(using = ToStringSerializer.class) Long spentMinor,
    @JsonSerialize(using = ToStringSerializer.class) Long limitMinor, Integer historyCount,
    BigDecimal zScore, BigDecimal budgetBurnRate, String explanation, String model, Instant createdAt,
    BigDecimal categorizationConfidence, String title, boolean read, boolean dismissed) {
  /** {@code title} is composed server-side (see {@link AlertTitleResolver}) and is deliberately
   * digit-free — money amounts are formatted for display only in the browser. {@code read}/{@code
   * dismissed} default to {@code false} — used where no per-user {@link AlertState} lookup was
   * performed (e.g. the dashboard's read-only recent-alerts summary). */
  public static AlertResponse from(Alert alert, String title) {
    return from(alert, title, false, false);
  }

  public static AlertResponse from(Alert alert, String title, boolean read, boolean dismissed) {
    return new AlertResponse(alert.getId(), alert.getExpenseId(), alert.getCategoryId(), alert.getPeriod(),
        alert.getCurrency(), alert.getAlertType(), alert.getThresholdPercent(), alert.getSpentMinor(),
        alert.getLimitMinor(), alert.getHistoryCount(), alert.getZScore(), alert.getBudgetBurnRate(),
        alert.getExplanation(), alert.getModel(), alert.getCreatedAt(),
        alert.getCategorizationConfidence(), title, read, dismissed);
  }
}
