import { apiFetchAuthenticated } from "@/lib/api-server";

export interface CurrencyTotal {
  currency: string;
  amountMinor: number;
}

export interface CategoryBreakdownEntry {
  categoryId: string;
  categoryName: string;
  amountMinor: number;
}

export interface MonthlySpend {
  /** ISO `YearMonth` string, e.g. `"2026-07"` — Spring's default Jackson JSR-310
   * serialization of `java.time.YearMonth` (`MonthlySpend.java` on the api side). */
  month: string;
  amountMinor: number;
}

export interface DashboardSummary {
  totalsThisMonth: CurrencyTotal[];
  totalsLastMonth: CurrencyTotal[];
  categoryBreakdown: CategoryBreakdownEntry[];
  monthlySeries: MonthlySpend[];
  reviewQueueCount: number;
  documentsProcessedToday: number;
}

export async function getDashboardSummary(): Promise<DashboardSummary | null> {
  const response = await apiFetchAuthenticated("/api/v1/dashboard/summary");
  if (!response.ok) {
    return null;
  }
  return (await response.json()) as DashboardSummary;
}

/**
 * `categoryBreakdown`/`monthlySeries` sum across currencies (api's own documented gap —
 * `DashboardSummaryResponse.java`), unlike `totalsThisMonth`, and cover a wider window (trailing
 * 6 months) than `totalsThisMonth` (current calendar month only) — an org with no spend yet this
 * month can still have both months of history and a currency to label it with. Falls back to
 * `totalsLastMonth` before giving up, since an org's currency essentially never changes
 * month-to-month; `undefined` means neither window has any data, so the caller should render an
 * empty state rather than fabricate a currency label.
 */
export function resolveDisplayCurrency(summary: DashboardSummary): string | undefined {
  return summary.totalsThisMonth[0]?.currency ?? summary.totalsLastMonth[0]?.currency;
}
