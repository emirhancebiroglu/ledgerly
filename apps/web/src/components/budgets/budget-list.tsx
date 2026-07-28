"use client";

import { useState, type FormEvent } from "react";
import { AlertCircle, CheckCircle2, CircleGauge, Pencil, Plus, TriangleAlert, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatMoney } from "@/lib/money";
import { createBudget, deleteBudget, updateBudget, type Budget, type BudgetInput } from "@/lib/budgets";
import type { Category } from "@/lib/categories";

interface BudgetListProps {
  initialBudgets: Budget[];
  categories: Category[];
}

type EditingBudget = Budget | "new" | null;

const STATUS_STYLE = {
  ON_TRACK: { label: "On track", icon: CheckCircle2, className: "bg-success-soft text-success-foreground", progress: "bg-success" },
  NEAR_THRESHOLD: { label: "80% reached", icon: TriangleAlert, className: "bg-warning-soft text-warning-foreground", progress: "bg-warning" },
  OVER_BUDGET: { label: "Limit exceeded", icon: AlertCircle, className: "bg-danger-soft text-danger-foreground", progress: "bg-danger" },
} as const;

function categoryLabel(categories: Category[], categoryId: string): string {
  return categories.find((category) => category.id === categoryId)?.name ?? "Deleted category";
}

function toInput(budget: Budget): BudgetInput {
  return {
    categoryId: budget.categoryId,
    period: budget.period,
    limitMinor: budget.limitMinor.toString(),
    currency: budget.currency,
  };
}

export function BudgetList({ initialBudgets, categories }: BudgetListProps) {
  const [budgets, setBudgets] = useState(initialBudgets);
  const [editing, setEditing] = useState<EditingBudget>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(input: BudgetInput) {
    setBusy(true);
    setError(null);
    const result = editing === "new" ? await createBudget(input) : await updateBudget(editing!.id, input);
    setBusy(false);
    if (!result.ok || !result.budget) {
      setError(result.ok ? "The server returned an incomplete budget." : result.message);
      return;
    }
    setBudgets((current) =>
      editing === "new"
        ? [...current, result.budget!].sort((a, b) => a.period.localeCompare(b.period))
        : current.map((budget) => (budget.id === result.budget!.id ? result.budget! : budget)),
    );
    setEditing(null);
  }

  async function remove(budget: Budget) {
    setBusy(true);
    setError(null);
    const result = await deleteBudget(budget.id);
    setBusy(false);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    setBudgets((current) => current.filter((item) => item.id !== budget.id));
  }

  return (
    <div className="flex flex-col gap-4">
      {error && (
        <div role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">Monthly limits use exact ledger minor units.</p>
        <Button onClick={() => { setError(null); setEditing("new"); }}>
          <Plus data-icon="inline-start" /> New budget
        </Button>
      </div>

      {editing && (
        <BudgetForm
          budget={editing === "new" ? undefined : editing}
          categories={categories}
          busy={busy}
          onCancel={() => { if (!busy) setEditing(null); }}
          onSave={save}
        />
      )}

      {budgets.length === 0 ? (
        <Card className="items-center p-8 text-center">
          <CircleGauge className="size-8 text-muted-foreground" aria-hidden />
          <div className="font-medium">No monthly budgets yet</div>
          <p className="max-w-md text-sm text-muted-foreground">Create a category limit to receive 80% and 100% threshold alerts.</p>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 shell:grid-cols-2">
          {budgets.map((budget) => {
            const state = STATUS_STYLE[budget.status];
            const Icon = state.icon;
            const percentage = Math.max(0, Math.round(budget.burnRate * 100));
            return (
              <Card key={budget.id} className="gap-4 p-5" data-testid="budget-card">
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="truncate font-heading text-base font-semibold">{categoryLabel(categories, budget.categoryId)}</h2>
                    <p className="mt-0.5 text-xs text-muted-foreground">{budget.period} · {budget.currency}</p>
                  </div>
                  <span className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold ${state.className}`}>
                    <Icon className="size-3.5" aria-hidden /> {state.label}
                  </span>
                </div>
                <div>
                  <div className="flex items-baseline justify-between gap-3 font-mono text-sm tabular-nums">
                    <span>{formatMoney(budget.spentMinor, budget.currency)}</span>
                    <span className="text-muted-foreground">of {formatMoney(budget.limitMinor, budget.currency)}</span>
                  </div>
                  <div className="mt-2 h-2 overflow-hidden rounded-full bg-muted" role="progressbar" aria-label={`${categoryLabel(categories, budget.categoryId)} budget usage`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(percentage, 100)}>
                    <div className={`h-full rounded-full ${state.progress}`} style={{ width: `${Math.min(percentage, 100)}%` }} />
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground"><span className="font-semibold text-foreground">{percentage}%</span> of this month&apos;s limit</p>
                </div>
                <div className="flex justify-end gap-1 border-t border-border pt-3">
                  <Button variant="ghost" size="sm" onClick={() => { setError(null); setEditing(budget); }} disabled={busy}><Pencil /> Edit</Button>
                  <Button variant="ghost" size="sm" className="text-danger hover:text-danger" onClick={() => remove(budget)} disabled={busy}><Trash2 /> Delete</Button>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}

function BudgetForm({ budget, categories, busy, onCancel, onSave }: {
  budget?: Budget;
  categories: Category[];
  busy: boolean;
  onCancel: () => void;
  onSave: (input: BudgetInput) => Promise<void>;
}) {
  const initial = budget ? toInput(budget) : { categoryId: categories[0]?.id ?? "", period: new Date().toISOString().slice(0, 7), limitMinor: "", currency: "EUR" };
  const [values, setValues] = useState(initial);
  const [validation, setValidation] = useState<string | null>(null);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!values.categoryId || !/^\d{4}-(0[1-9]|1[0-2])$/.test(values.period) || !/^\d+$/.test(values.limitMinor) || BigInt(values.limitMinor) <= BigInt(0) || BigInt(values.limitMinor) > BigInt("9223372036854775807") || !/^[A-Z]{3}$/.test(values.currency)) {
      setValidation("Choose a category, calendar month, positive whole-number minor limit, and three-letter currency.");
      return;
    }
    setValidation(null);
    void onSave(values);
  }

  return (
    <Card className="gap-4 p-5">
      <div><h2 className="font-heading text-base font-semibold">{budget ? "Edit budget" : "New budget"}</h2><p className="text-sm text-muted-foreground">Amounts are integer minor units, exactly as stored by the ledger.</p></div>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" onSubmit={submit}>
        <div className="grid gap-2"><Label htmlFor="budget-category">Category</Label><select id="budget-category" value={values.categoryId} onChange={(event) => setValues({ ...values, categoryId: event.target.value })} className="h-8 rounded-lg border border-input bg-background px-2.5 text-sm" disabled={busy}>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></div>
        <div className="grid gap-2"><Label htmlFor="budget-period">Month</Label><Input id="budget-period" type="month" value={values.period} onChange={(event) => setValues({ ...values, period: event.target.value })} disabled={busy} /></div>
        <div className="grid gap-2"><Label htmlFor="budget-limit">Monthly limit (minor units)</Label><Input id="budget-limit" inputMode="numeric" pattern="[0-9]*" value={values.limitMinor} onChange={(event) => setValues({ ...values, limitMinor: event.target.value })} disabled={busy} /></div>
        <div className="grid gap-2"><Label htmlFor="budget-currency">Currency</Label><Input id="budget-currency" maxLength={3} value={values.currency} onChange={(event) => setValues({ ...values, currency: event.target.value.toUpperCase() })} disabled={busy} /></div>
        {validation && <p role="alert" className="sm:col-span-2 text-sm text-danger">{validation}</p>}
        <div className="flex justify-end gap-2 sm:col-span-2"><Button type="button" variant="outline" onClick={onCancel} disabled={busy}>Cancel</Button><Button type="submit" disabled={busy || categories.length === 0}>{busy ? "Saving…" : "Save budget"}</Button></div>
      </form>
    </Card>
  );
}
