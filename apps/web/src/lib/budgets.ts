export type BudgetStatus = "ON_TRACK" | "NEAR_THRESHOLD" | "OVER_BUDGET";

export interface Budget {
  id: string;
  categoryId: string;
  period: string;
  limitMinor: bigint;
  currency: string;
  spentMinor: bigint;
  burnRate: number;
  status: BudgetStatus;
  createdAt: string;
  updatedAt: string;
}

export interface BudgetInput {
  categoryId: string;
  period: string;
  /** Decimal string so JSON cannot round an exact minor-unit integer before the API receives it. */
  limitMinor: string;
  currency: string;
}

export type BudgetMutationResult =
  | { ok: true; budget?: Budget }
  | { ok: false; status: number; message: string };

async function mutateBudget(
  path: string,
  method: "POST" | "PUT" | "DELETE",
  input?: BudgetInput,
): Promise<BudgetMutationResult> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: {
        "Idempotency-Key": crypto.randomUUID(),
        ...(input ? { "Content-Type": "application/json" } : {}),
      },
      body: input ? JSON.stringify(input) : undefined,
    });
  } catch {
    return { ok: false, status: 0, message: "We couldn't reach Ledgerly. Your saved budgets are unchanged." };
  }

  if (!response.ok) {
    let message = "We couldn't save this budget. Please try again.";
    try {
      const problem = (await response.json()) as { detail?: string };
      message = problem.detail ?? message;
    } catch {
      // Keep a usable fallback for non-Problem Details responses.
    }
    return { ok: false, status: response.status, message };
  }

  if (method === "DELETE") {
    return { ok: true };
  }
  return { ok: true, budget: parseBudget(await response.json()) };
}

function parseBudget(value: unknown): Budget {
  const budget = value as Omit<Budget, "limitMinor" | "spentMinor"> & {
    limitMinor: string | number;
    spentMinor: string | number;
  };
  return { ...budget, limitMinor: parseMinor(budget.limitMinor), spentMinor: parseMinor(budget.spentMinor) };
}

export function parseMinor(value: string | number): bigint {
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new Error("Unsafe minor-unit value from API");
    return BigInt(value);
  }
  if (!/^-?\d+$/.test(value)) throw new Error("Invalid minor-unit value from API");
  return BigInt(value);
}

/** Converts a human decimal amount to exact cents without a floating-point round trip. */
export function decimalToMinor(value: string): bigint | null {
  const match = value.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
  if (!match) return null;
  const minor = BigInt(match[1]) * BigInt(100) + BigInt((match[2] ?? "").padEnd(2, "0") || "0");
  return minor <= BigInt("9223372036854775807") ? minor : null;
}

export function minorToDecimal(value: bigint): string {
  return `${value / BigInt(100)}.${(value % BigInt(100)).toString().padStart(2, "0")}`;
}

export function createBudget(input: BudgetInput): Promise<BudgetMutationResult> {
  return mutateBudget("/api/budgets", "POST", input);
}

export function updateBudget(id: string, input: BudgetInput): Promise<BudgetMutationResult> {
  return mutateBudget(`/api/budgets/${id}`, "PUT", input);
}

export function deleteBudget(id: string): Promise<BudgetMutationResult> {
  return mutateBudget(`/api/budgets/${id}`, "DELETE");
}
