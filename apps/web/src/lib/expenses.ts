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

export async function listExpenses(params: ListExpensesParams = {}): Promise<Expense[]> {
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
    return [];
  }
  return (await response.json()) as Expense[];
}
