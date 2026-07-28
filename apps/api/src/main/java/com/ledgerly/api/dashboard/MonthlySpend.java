package com.ledgerly.api.dashboard;

import java.time.YearMonth;

/** One point of the dashboard's spend-over-time chart. {@code amountMinor} is 0 for a month with
 * no POSTED expenses — the series has no gaps, only zero months. */
public record MonthlySpend(YearMonth month, long amountMinor) {}
