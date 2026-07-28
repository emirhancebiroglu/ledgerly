package com.ledgerly.api.anomaly;

import java.util.List;
import java.util.UUID;

/** Port for the advisory {@code ai POST /anomaly} call. */
public interface AnomalyClient {

  String assess(
      UUID expenseId,
      UUID categoryId,
      String currency,
      long amountMinor,
      List<AnomalyHistoryEntry> history,
      AnomalyBudgetSnapshot budget);
}
