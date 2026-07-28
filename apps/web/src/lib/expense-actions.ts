export type ExpenseActionResult =
  | { ok: true }
  | { ok: false; status: number; message: string };

async function postAction(path: string, body?: unknown): Promise<ExpenseActionResult> {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      "Idempotency-Key": crypto.randomUUID(),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    let message = "Something went wrong. Please try again.";
    if (response.status === 409) {
      message = "This expense was already resolved elsewhere.";
    }
    try {
      const problem = (await response.json()) as { detail?: string };
      message = problem.detail ?? message;
    } catch {
      // Non-JSON error body — keep the status-derived message.
    }
    return { ok: false, status: response.status, message };
  }

  return { ok: true };
}

export function approveExpense(id: string): Promise<ExpenseActionResult> {
  return postAction(`/api/expenses/${id}/approve`);
}

export function correctExpense(id: string, categoryId: string): Promise<ExpenseActionResult> {
  return postAction(`/api/expenses/${id}/correct`, { categoryId });
}
