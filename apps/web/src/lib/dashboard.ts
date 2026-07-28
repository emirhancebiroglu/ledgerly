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
  year: number;
  month: number;
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
