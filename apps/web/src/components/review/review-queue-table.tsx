"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
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
  const router = useRouter();
  const [expenses, setExpenses] = useState(initialExpenses);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState<Set<string>>(new Set());
  const [errors, setErrors] = useState<RowError[]>([]);
  const [announcement, setAnnouncement] = useState("");
  const inFlight = useRef<Set<string>>(new Set());
  const expensesRef = useRef(initialExpenses);

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

  function removeOptimistically(id: string): { expense: Expense; index: number } | undefined {
    const index = expensesRef.current.findIndex((e) => e.id === id);
    if (index === -1) return undefined;
    const removed = { expense: expensesRef.current[index], index };
    expensesRef.current = expensesRef.current.filter((e) => e.id !== id);
    setExpenses(expensesRef.current);
    setSelected((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
    return removed;
  }

  function rollback(removedExpense: Expense, index: number, message: string) {
    const next = [...expensesRef.current];
    next.splice(Math.min(index, next.length), 0, removedExpense);
    expensesRef.current = next;
    setExpenses(next);
    setErrors((prev) => [...prev.filter((e) => e.id !== removedExpense.id), { id: removedExpense.id, message }]);
  }

  async function approveOne(id: string) {
    if (inFlight.current.has(id)) return;
    inFlight.current.add(id);
    markPending(id, true);
    setErrors((prev) => prev.filter((e) => e.id !== id));
    const removed = removeOptimistically(id);

    const result = await approveExpense(id);
    inFlight.current.delete(id);
    markPending(id, false);

    if (result.ok && removed) {
      setAnnouncement(`${removed.expense.vendor ?? "Expense"} approved`);
      // The sidebar's review-queue count is read once by the server-rendered layout and does not
      // otherwise learn this row left the queue until a hard reload.
      router.refresh();
    } else if (!result.ok && removed) {
      rollback(removed.expense, removed.index, result.message);
    }
  }

  async function approveSelected() {
    await Promise.all(Array.from(selected).map((id) => approveOne(id)));
  }

  async function correctOne(id: string, categoryId: string) {
    if (inFlight.current.has(id)) return;
    inFlight.current.add(id);
    markPending(id, true);
    setErrors((prev) => prev.filter((e) => e.id !== id));
    const removed = removeOptimistically(id);

    const result = await correctExpense(id, categoryId);
    inFlight.current.delete(id);
    markPending(id, false);

    if (result.ok && removed) {
      setAnnouncement(`${removed.expense.vendor ?? "Expense"} corrected and resolved`);
      router.refresh();
    } else if (!result.ok && removed) {
      rollback(removed.expense, removed.index, result.message);
    }
  }

  const liveRegion = (
    <div role="status" aria-live="polite" className="sr-only">
      {announcement}
    </div>
  );

  if (expenses.length === 0) {
    return (
      <>
        {liveRegion}
        <Card className="p-8 text-center text-sm text-muted-foreground">
          Nothing needs review right now.
        </Card>
      </>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {liveRegion}
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
          // Legacy/nullable JSON fields are omitted on the wire, so an unclassified category can
          // arrive as either null or undefined despite the application-level type.
          const requiresCategorySelection = expense.categoryId == null;
          return (
            <div key={expense.id} className="border-b border-border/60 last:border-b-0">
              <div className="grid grid-cols-1 items-start gap-1.5 px-5 py-3.5 shell:grid-cols-[auto_1.5fr_1fr_1.5fr_auto] shell:items-center shell:gap-2.5">
                <div className="flex items-center">
                  <input
                    type="checkbox"
                    checked={selected.has(expense.id)}
                    onChange={() => toggle(expense.id)}
                    aria-label={`Select ${expense.vendor ?? "expense"} for bulk approval`}
                    disabled={pending.has(expense.id) || requiresCategorySelection}
                    className="size-4"
                  />
                </div>
                <div className="text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
                <div className="font-mono text-[13px] tabular-nums">
                  {formatMoney(expense.amountMinor, expense.currency)}
                </div>
                <div className="text-[12px] text-muted-foreground">
                  {requiresCategorySelection
                    ? "Needs review — choose a category before posting"
                    : expense.citation ??
                    `Confidence below threshold (${Math.round(expense.categorizationConfidence * 100)}%)`}
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {!requiresCategorySelection && (
                    <button
                      type="button"
                      onClick={() => approveOne(expense.id)}
                      disabled={pending.has(expense.id)}
                      className="rounded-md bg-success-soft px-2.5 py-1 text-[11.5px] font-semibold text-success-foreground transition-all hover:-translate-y-px disabled:opacity-50"
                    >
                      Approve
                    </button>
                  )}
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
