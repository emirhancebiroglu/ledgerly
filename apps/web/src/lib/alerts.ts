import { parseMinor } from "@/lib/budgets";

export type AlertType = "BUDGET_THRESHOLD" | "ANOMALY_HIGH" | "LOW_CONFIDENCE";

export interface Alert {
  id: string;
  expenseId: string;
  categoryId: string | null;
  period: string;
  currency: string;
  alertType: AlertType;
  thresholdPercent: number | null;
  spentMinor: bigint | null;
  limitMinor: bigint | null;
  historyCount: number | null;
  zScore: number | null;
  budgetBurnRate: number | null;
  explanation: string | null;
  model: string | null;
  createdAt: string;
  categorizationConfidence: number | null;
  /** Composed server-side, digit-free — see `AlertTitleResolver` on the API. Money amounts are
   * formatted for display only in the browser (`formatMoney`), never embedded in this string. */
  title: string;
  read: boolean;
  dismissed: boolean;
}

interface RawAlert
  extends Omit<Alert, "spentMinor" | "limitMinor"> {
  spentMinor: string | number | null;
  limitMinor: string | number | null;
}

export function parseAlert(raw: RawAlert): Alert {
  return {
    ...raw,
    spentMinor: raw.spentMinor == null ? null : parseMinor(raw.spentMinor),
    limitMinor: raw.limitMinor == null ? null : parseMinor(raw.limitMinor),
  };
}

export type AlertMutationResult =
  | { ok: true }
  | { ok: false; status: number; message: string };

async function mutateAlert(path: string): Promise<AlertMutationResult> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
    });
  } catch {
    return { ok: false, status: 0, message: "We couldn't reach Ledgerly. Try again." };
  }

  if (!response.ok) {
    let message = "We couldn't update this alert. Please try again.";
    try {
      const problem = (await response.json()) as { detail?: string };
      message = problem.detail ?? message;
    } catch {
      // Keep a usable fallback for non-Problem Details responses.
    }
    return { ok: false, status: response.status, message };
  }
  return { ok: true };
}

export function markAlertRead(id: string): Promise<AlertMutationResult> {
  return mutateAlert(`/api/alerts/${id}/read`);
}

export function markAllAlertsRead(): Promise<AlertMutationResult> {
  return mutateAlert("/api/alerts/read-all");
}

export function dismissAlert(id: string): Promise<AlertMutationResult> {
  return mutateAlert(`/api/alerts/${id}/dismiss`);
}
