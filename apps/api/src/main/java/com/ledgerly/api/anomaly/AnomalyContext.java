package com.ledgerly.api.anomaly;

import java.util.List;

record AnomalyContext(List<AnomalyHistoryEntry> history, AnomalyBudgetSnapshot budget) {}
