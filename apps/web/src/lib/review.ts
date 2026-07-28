import { apiFetchAuthenticated } from "@/lib/api-server";
import type { Expense } from "@/lib/expenses";

export async function listReviewQueue(): Promise<Expense[]> {
  const response = await apiFetchAuthenticated("/api/v1/expenses?status=NEEDS_REVIEW");
  if (!response.ok) {
    return [];
  }
  return (await response.json()) as Expense[];
}
