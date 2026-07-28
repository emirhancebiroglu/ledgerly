import { apiFetchAuthenticated } from "@/lib/api-server";
import type { ExpenseStatus } from "@/components/status-chip";

export interface Expense {
  id: string;
  documentId: string;
  vendor: string | null;
  categoryId: string | null;
  ledgerTransactionId: string | null;
  amountMinor: number;
  currency: string;
  categorizationConfidence: number;
  citation: string | null;
  status: ExpenseStatus;
  createdAt: string;
}

export interface ListExpensesParams {
  status?: string;
  search?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export type ListExpensesResult =
  | { ok: true; expenses: Expense[] }
  | { ok: false; status: number; message: string };

/**
 * `ExpenseController.list` 400s on an unrecognized `status`/`sort` (`ExpenseListQuery.parse`) —
 * distinct from a query that's valid but matches nothing, which is a normal 200 with an empty
 * array. Callers need to tell those apart: an empty array renders an empty state, a 400 renders
 * an error state with the server's own message.
 */
export async function listExpenses(params: ListExpensesParams = {}): Promise<ListExpensesResult> {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.search) query.set("search", params.search);
  if (params.sort) query.set("sort", params.sort);
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));

  const queryString = query.toString();
  const response = await apiFetchAuthenticated(
    `/api/v1/expenses${queryString ? `?${queryString}` : ""}`,
  );

  if (!response.ok) {
    let message = "Something went wrong loading expenses.";
    try {
      const problem = (await response.json()) as { detail?: string };
      message = problem.detail ?? message;
    } catch {
      // Non-JSON error body — keep the generic message.
    }
    return { ok: false, status: response.status, message };
  }

  return { ok: true, expenses: (await response.json()) as Expense[] };
}
