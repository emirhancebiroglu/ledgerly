package com.ledgerly.api.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact spend and a decimal ratio for a budget; monetary values remain integer minor units. */
public record BudgetUsage(long spentMinor, BigDecimal burnRate, BudgetStatus status) {

  private static final BigDecimal NEAR_THRESHOLD = new BigDecimal("0.80");

  public static BudgetUsage of(long spentMinor, long limitMinor) {
    BigDecimal burnRate =
        BigDecimal.valueOf(spentMinor)
            .divide(BigDecimal.valueOf(limitMinor), 4, RoundingMode.HALF_UP);
    BudgetStatus status =
        burnRate.compareTo(BigDecimal.ONE) >= 0
            ? BudgetStatus.OVER_BUDGET
            : burnRate.compareTo(NEAR_THRESHOLD) >= 0
                ? BudgetStatus.NEAR_THRESHOLD
                : BudgetStatus.ON_TRACK;
    return new BudgetUsage(spentMinor, burnRate, status);
  }
}
