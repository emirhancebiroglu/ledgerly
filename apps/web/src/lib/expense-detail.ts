import { apiFetchAuthenticated } from "@/lib/api-server";
import type { ExpenseStatus } from "@/components/status-chip";

export interface LedgerEntryView {
  accountId: string;
  accountName: string;
  direction: "DEBIT" | "CREDIT";
  amountMinor: number;
  currency: string;
}

export type DocumentStatus = "PENDING" | "PROCESSING" | "EXTRACTED" | "NEEDS_REVIEW" | "FAILED";

export interface DocumentMeta {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  status: DocumentStatus;
  proposal: string | null;
  failureReason: string | null;
  createdAt: string;
}

export interface ExpenseDetail {
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
  ledgerEntries: LedgerEntryView[];
  document: DocumentMeta;
  invoiceNumber: string | null;
  documentDate: string | null;
  taxMinor: string | null;
  activity: DocumentActivity[];
}

export type DocumentActivityStage =
  | "UPLOADED"
  | "EXTRACTING"
  | "CATEGORIZING"
  | "DRAFTING_LEDGER"
  | "POSTED"
  | "NEEDS_REVIEW"
  | "FAILED"
  | "CATEGORIZATION_FAILED";

export interface DocumentActivity {
  id: number;
  stage: DocumentActivityStage;
  detail: string | null;
  createdAt: string;
}

export type GetExpenseDetailResult =
  | { ok: true; expense: ExpenseDetail }
  | { ok: false; status: number };

export async function getExpenseDetail(id: string): Promise<GetExpenseDetailResult> {
  const response = await apiFetchAuthenticated(`/api/v1/expenses/${id}/detail`);
  if (!response.ok) {
    return { ok: false, status: response.status };
  }
  return { ok: true, expense: (await response.json()) as ExpenseDetail };
}
