package com.ledgerly.api.dashboard;

import java.util.List;
import com.ledgerly.api.alert.AlertResponse;

/**
 * {@code GET /api/v1/dashboard/summary} — everything the M7b dashboard's KPI card, category
 * breakdown, spend-over-time chart, and summary card need in one call.
 *
 * @param totalsThisMonth POSTED spend for the current calendar month, by currency — see {@link
 *     CurrencyTotal} for why this is a list, not a single number.
 * @param totalsLastMonth same shape, for the KPI card's "vs last month" caption.
 * @param categoryBreakdown current month's spend by category, one row per (category, currency),
 *     highest first within each currency. An org whose expenses in one category span more than
 *     one currency sees one row per currency rather than a cross-currency sum.
 * @param monthlySeries the trailing 6 months (oldest first), one complete series per currency
 *     that appears anywhere in the window — every currency is zero-filled for any month in the
 *     window with no POSTED spend in that currency.
 * @param reviewQueueCount count of {@code NEEDS_REVIEW} expenses.
 * @param documentsProcessedToday documents that reached a terminal status today.
 */
public record DashboardSummaryResponse(
    List<CurrencyTotal> totalsThisMonth,
    List<CurrencyTotal> totalsLastMonth,
    List<CategoryBreakdownEntry> categoryBreakdown,
    List<MonthlySpend> monthlySeries,
    long reviewQueueCount,
    long documentsProcessedToday,
    long alertCount,
    List<AlertResponse> recentAlerts) {}
