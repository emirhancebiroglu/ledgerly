package com.ledgerly.api.anomaly;

import java.util.UUID;

/** Strictly mapped, still-advisory response from {@code ai /anomaly}. */
public record AnomalyResponse(
    UUID expenseId,
    AnomalyRisk risk,
    int historyCount,
    double zScore,
    Double budgetBurnRate,
    String explanation,
    String model) {}
