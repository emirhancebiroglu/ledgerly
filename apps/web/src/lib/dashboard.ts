import { apiFetchAuthenticated } from "@/lib/api-server";

export interface CurrencyTotal {
  currency: string;
  amountMinor: number;
}

export interface CategoryBreakdownEntry {
  categoryId: string;
  categoryName: string;
  currency: string;
  amountMinor: number;
}

export interface MonthlySpend {
  /** ISO `YearMonth` string, e.g. `"2026-07"` — Spring's default Jackson JSR-310
   * serialization of `java.time.YearMonth` (`MonthlySpend.java` on the api side). */
  month: string;
  currency: string;
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
 * Splits any list of currency-bearing rows (`CategoryBreakdownEntry`, `MonthlySpend`, ...) into
 * one array per currency, sorted by currency code — the one grouping pass every per-currency
 * dashboard section needs, so `CategoryBreakdown` and `SpendOverTimeChart` share it instead of
 * each keeping its own copy that can drift the way `EXPENSE_GRID_TEMPLATE`'s track-list once did
 * between the expenses list and dashboard recent-expenses.
 */
export function groupByCurrency<T extends { currency: string }>(rows: T[]): Array<[string, T[]]> {
  const sections = new Map<string, T[]>();
  for (const row of rows) {
    const existing = sections.get(row.currency);
    if (existing) {
      existing.push(row);
    } else {
      sections.set(row.currency, [row]);
    }
  }
  return [...sections.entries()].sort(([a], [b]) => a.localeCompare(b));
}
