package com.ledgerly.api.dashboard;

import java.util.List;

/**
 * {@code GET /api/v1/dashboard/summary} — everything the M7b dashboard's KPI card, category
 * breakdown, spend-over-time chart, and summary card need in one call.
 *
 * @param totalsThisMonth POSTED spend for the current calendar month, by currency — see {@link
 *     CurrencyTotal} for why this is a list, not a single number.
 * @param totalsLastMonth same shape, for the KPI card's "vs last month" caption.
 * @param categoryBreakdown current month's spend by category, highest first. Unlike {@code
 *     totalsThisMonth}/{@code totalsLastMonth}, this is a single amount per category regardless
 *     of currency — an org whose expenses in one category genuinely span more than one currency
 *     will see them summed here. Narrower than {@code totalsThisMonth}'s guarantee; revisit if a
 *     real org hits this.
 * @param monthlySeries the trailing 6 months (oldest first), zero-filled for any month with no
 *     POSTED spend. Same currency caveat as {@code categoryBreakdown} — sums across currencies,
 *     no per-currency split.
 * @param reviewQueueCount count of {@code NEEDS_REVIEW} expenses.
 * @param documentsProcessedToday documents that reached a terminal status today.
 */
public record DashboardSummaryResponse(
    List<CurrencyTotal> totalsThisMonth,
    List<CurrencyTotal> totalsLastMonth,
    List<CategoryBreakdownEntry> categoryBreakdown,
    List<MonthlySpend> monthlySeries,
    long reviewQueueCount,
    long documentsProcessedToday) {}
