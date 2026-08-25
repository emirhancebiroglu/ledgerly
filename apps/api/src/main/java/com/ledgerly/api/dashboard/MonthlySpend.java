package com.ledgerly.api.dashboard;

import java.time.YearMonth;

/**
 * One point of the dashboard's spend-over-time chart, for one currency. {@code amountMinor} is 0
 * for a month with no POSTED expenses in that currency — every currency present anywhere in the
 * window gets a complete, gap-free series across every month in the window, zero-filled where
 * that currency had no spend in a given month.
 */
public record MonthlySpend(YearMonth month, String currency, long amountMinor) {}
