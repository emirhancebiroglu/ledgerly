"use client";

import { useState } from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { formatMoney } from "@/lib/money";
import { approveExpense, correctExpense } from "@/lib/expense-actions";
import type { Expense } from "@/lib/expenses";
import type { Category } from "@/lib/categories";

interface RowError {
  id: string;
  message: string;
}

interface ReviewQueueTableProps {
  initialExpenses: Expense[];
  categories: Category[];
}

export function ReviewQueueTable({ initialExpenses, categories }: ReviewQueueTableProps) {
  const [expenses, setExpenses] = useState(initialExpenses);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState<Set<string>>(new Set());
  const [errors, setErrors] = useState<RowError[]>([]);

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function markPending(id: string, isPending: boolean) {
    setPending((prev) => {
      const next = new Set(prev);
      if (isPending) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  function removeOptimistically(id: string): Expense | undefined {
    const removed = expenses.find((e) => e.id === id);
    setExpenses((prev) => prev.filter((e) => e.id !== id));
    setSelected((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
    return removed;
  }

  function rollback(removedExpense: Expense, message: string) {
    setExpenses((prev) => [...prev, removedExpense].sort((a, b) => a.id.localeCompare(b.id)));
    setErrors((prev) => [...prev.filter((e) => e.id !== removedExpense.id), { id: removedExpense.id, message }]);
  }

  async function approveOne(id: string) {
    markPending(id, true);
    setErrors((prev) => prev.filter((e) => e.id !== id));
    const removed = removeOptimistically(id);

    const result = await approveExpense(id);
    markPending(id, false);

    if (!result.ok && removed) {
      rollback(removed, result.message);
    }
  }

  async function approveSelected() {
    await Promise.all(Array.from(selected).map((id) => approveOne(id)));
  }

  async function correctOne(id: string, categoryId: string) {
    markPending(id, true);
    setErrors((prev) => prev.filter((e) => e.id !== id));
    const removed = removeOptimistically(id);

    const result = await correctExpense(id, categoryId);
    markPending(id, false);

    if (!result.ok && removed) {
      rollback(removed, result.message);
    }
  }

  if (expenses.length === 0) {
    return (
      <Card className="p-8 text-center text-sm text-muted-foreground">
        Nothing needs review right now.
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div className="text-[13px] text-muted-foreground">
          <span className="font-bold text-foreground">{expenses.length} items</span> need review
          before posting to the ledger
        </div>
        {selected.size > 0 && (
          <Button onClick={approveSelected} className="bg-primary text-primary-foreground">
            Approve selected
          </Button>
        )}
      </div>

      <Card className="gap-0 overflow-hidden py-0">
        <div className="hidden border-b border-border px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-muted-foreground uppercase shell:grid shell:grid-cols-[auto_1.5fr_1fr_1.5fr_auto] shell:items-center shell:gap-2.5">
          <div />
          <div>Vendor</div>
          <div>Amount</div>
          <div>Flagged for</div>
          <div>Actions</div>
        </div>
        {expenses.map((expense) => {
          const error = errors.find((e) => e.id === expense.id);
          return (
            <div key={expense.id} className="border-b border-border/60 last:border-b-0">
              <div className="grid grid-cols-1 items-start gap-1.5 px-5 py-3.5 shell:grid-cols-[auto_1.5fr_1fr_1.5fr_auto] shell:items-center shell:gap-2.5">
                <div className="flex items-center">
                  <input
                    type="checkbox"
                    checked={selected.has(expense.id)}
                    onChange={() => toggle(expense.id)}
                    aria-label={`Select ${expense.vendor ?? "expense"} for bulk approval`}
                    disabled={pending.has(expense.id)}
                    className="size-4"
                  />
                </div>
                <div className="text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
                <div className="font-mono text-[13px] tabular-nums">
                  {formatMoney(expense.amountMinor, expense.currency)}
                </div>
                <div className="text-[12px] text-muted-foreground">
                  {expense.citation ??
                    `Confidence below threshold (${Math.round(expense.categorizationConfidence * 100)}%)`}
                </div>
                <div className="flex flex-wrap gap-1.5">
                  <button
                    type="button"
                    onClick={() => approveOne(expense.id)}
                    disabled={pending.has(expense.id)}
                    className="rounded-md bg-success-soft px-2.5 py-1 text-[11.5px] font-semibold text-success-foreground transition-all hover:-translate-y-px disabled:opacity-50"
                  >
                    Approve
                  </button>
                  <label className="sr-only" htmlFor={`correct-${expense.id}`}>
                    Correct category for {expense.vendor ?? "expense"}
                  </label>
                  <select
                    id={`correct-${expense.id}`}
                    disabled={pending.has(expense.id)}
                    defaultValue=""
                    onChange={(event) => {
                      const categoryId = event.target.value;
                      if (categoryId) {
                        correctOne(expense.id, categoryId);
                      }
                    }}
                    className="rounded-md bg-muted px-2.5 py-1 text-[11.5px] font-semibold text-muted-foreground disabled:opacity-50"
                  >
                    <option value="" disabled>
                      Correct…
                    </option>
                    {categories.map((category) => (
                      <option key={category.id} value={category.id}>
                        {category.name}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              {error && (
                <div
                  role="alert"
                  className="border-t border-danger/30 bg-danger-soft px-5 py-2 text-[12px] text-danger"
                >
                  {error.message}
                </div>
              )}
            </div>
          );
        })}
      </Card>
    </div>
  );
}
