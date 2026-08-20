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

export interface AlertSummary {
  id: string;
  expenseId: string;
  categoryId: string;
  period: string;
  currency: string;
  alertType: "BUDGET_THRESHOLD" | "ANOMALY_HIGH";
  thresholdPercent: number | null;
  spentMinor: bigint | null;
  limitMinor: bigint | null;
  historyCount: number | null;
  zScore: number | null;
  budgetBurnRate: number | null;
  explanation: string | null;
  createdAt: string;
}

export interface DashboardSummary {
  totalsThisMonth: CurrencyTotal[];
  totalsLastMonth: CurrencyTotal[];
  categoryBreakdown: CategoryBreakdownEntry[];
  monthlySeries: MonthlySpend[];
  reviewQueueCount: number;
  documentsProcessedToday: number;
  /**
   * Still returned by `api` and still parsed here, but nothing renders it: the dashboard's alerts
   * card was removed because alert records belong on their own screen rather than on a screen that
   * is not about them. Kept so the deferred Alerts route has its data path intact; delete both
   * fields instead of adding a second reader if that route is dropped for good.
   */
  alertCount: number;
  recentAlerts: AlertSummary[];
}

export async function getDashboardSummary(): Promise<DashboardSummary | null> {
  const response = await apiFetchAuthenticated("/api/v1/dashboard/summary");
  if (!response.ok) {
    return null;
  }
  const summary = (await response.json()) as Omit<DashboardSummary, "recentAlerts"> & {
    recentAlerts: Array<Omit<AlertSummary, "spentMinor" | "limitMinor"> & {
      spentMinor: string | number | null;
      limitMinor: string | number | null;
    }>;
  };
  return {
    ...summary,
    recentAlerts: summary.recentAlerts.map((alert) => ({
      ...alert,
      spentMinor: parseOptionalMinor(alert.spentMinor),
      limitMinor: parseOptionalMinor(alert.limitMinor),
    })),
  };
}

function parseOptionalMinor(value: string | number | null): bigint | null {
  if (value === null) return null;
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new Error("Unsafe minor-unit value from API");
    return BigInt(value);
  }
  if (!/^-?\d+$/.test(value)) throw new Error("Invalid minor-unit value from API");
  return BigInt(value);
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
