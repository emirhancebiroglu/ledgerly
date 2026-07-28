package com.ledgerly.api.anomaly;

import java.time.Instant;
import java.util.UUID;

/** Published inside a posting transaction; handled only after its successful commit. */
public record ExpensePostedEvent(UUID organizationId, UUID expenseId, Instant postedAt, UUID actor) {}
