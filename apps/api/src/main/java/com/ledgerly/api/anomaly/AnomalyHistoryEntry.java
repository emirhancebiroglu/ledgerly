package com.ledgerly.api.anomaly;

import java.time.Instant;

/** One already-posted historical amount; the candidate never appears here. */
public record AnomalyHistoryEntry(long amountMinor, Instant postedAt) {}
