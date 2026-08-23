package com.ledgerly.api.expense;

import java.util.UUID;

/** An earlier expense {@link DuplicateMatcher} believes the candidate duplicates. */
public record DuplicateMatch(UUID matchedExpenseId, DuplicateMatchTier tier) {}
