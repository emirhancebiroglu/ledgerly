package com.ledgerly.api.dashboard;

import java.util.UUID;

/** One row of the dashboard's category-breakdown chart: how much of total spend a category is. */
public record CategoryBreakdownEntry(UUID categoryId, String categoryName, long amountMinor) {}
