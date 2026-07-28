package com.ledgerly.api.anomaly;

/** Exact matching budget facts at advisor read time, all monetary values in minor units. */
public record AnomalyBudgetSnapshot(String period, long limitMinor, long spentMinor) {}
