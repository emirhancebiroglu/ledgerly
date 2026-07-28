import { apiFetchAuthenticated } from "@/lib/api-server";
import { parseMinor, type Budget } from "@/lib/budgets";

export async function listBudgets(): Promise<{ ok: true; budgets: Budget[] } | { ok: false }> {
  const response = await apiFetchAuthenticated("/api/v1/budgets?size=100");
  if (!response.ok) {
    return { ok: false };
  }
  const payload = (await response.json()) as Array<Omit<Budget, "limitMinor" | "spentMinor"> & { limitMinor: string | number; spentMinor: string | number }>;
  return { ok: true, budgets: payload.map((budget) => ({ ...budget, limitMinor: parseMinor(budget.limitMinor), spentMinor: parseMinor(budget.spentMinor) })) };
}
