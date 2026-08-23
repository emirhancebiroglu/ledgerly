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
    BigDecimal categorizationConfidence, UUID matchedExpenseId, String duplicateTier,
    MatchedExpenseSummary matchedExpense, MatchedExpenseSummary triggeringExpense,
    String title, boolean read, boolean dismissed) {
  /** {@code title} is composed server-side (see {@link AlertTitleResolver}) and is deliberately
   * digit-free — money amounts are formatted for display only in the browser. {@code read}/{@code
   * dismissed} default to {@code false} — used where no per-user {@link AlertState} lookup was
   * performed (e.g. the dashboard's read-only recent-alerts summary). {@code matchedExpense} and
   * {@code triggeringExpense} are only populated for {@code DUPLICATE_SUSPECTED} — the earlier and
   * newer entry respectively — and even then either may be {@code null} if that expense was since
   * deleted; the ids on their own are not enough for the client to render the comparison without a
   * second round trip. */
  public static AlertResponse from(Alert alert, String title) {
    return from(alert, title, false, false, null, null);
  }

  public static AlertResponse from(
      Alert alert, String title, boolean read, boolean dismissed,
      MatchedExpenseSummary matchedExpense, MatchedExpenseSummary triggeringExpense) {
    return new AlertResponse(alert.getId(), alert.getExpenseId(), alert.getCategoryId(), alert.getPeriod(),
        alert.getCurrency(), alert.getAlertType(), alert.getThresholdPercent(), alert.getSpentMinor(),
        alert.getLimitMinor(), alert.getHistoryCount(), alert.getZScore(), alert.getBudgetBurnRate(),
        alert.getExplanation(), alert.getModel(), alert.getCreatedAt(),
        alert.getCategorizationConfidence(), alert.getMatchedExpenseId(), alert.getDuplicateTier(),
        matchedExpense, triggeringExpense, title, read, dismissed);
  }

  public record MatchedExpenseSummary(
      String vendor,
      @JsonSerialize(using = ToStringSerializer.class) long amountMinor,
      String currency,
      Instant createdAt) {}
}
