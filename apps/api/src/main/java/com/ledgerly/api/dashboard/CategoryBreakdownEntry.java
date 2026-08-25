package com.ledgerly.api.dashboard;

import java.util.UUID;

/**
 * One row of the dashboard's category-breakdown chart: how much of total spend a category is, in
 * one currency. A category with POSTED expenses in more than one currency produces one row per
 * currency rather than a single cross-currency sum — see {@link CurrencyTotal} for why summing
 * different currencies' minor units is never a real amount.
 */
public record CategoryBreakdownEntry(
    UUID categoryId, String categoryName, String currency, long amountMinor) {}
