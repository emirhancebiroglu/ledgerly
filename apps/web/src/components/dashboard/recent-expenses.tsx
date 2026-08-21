import Link from "next/link";
import { Card } from "@/components/ui/card";
import { StatusChip } from "@/components/status-chip";
import { formatMoney } from "@/lib/money";
import type { Expense } from "@/lib/expenses";

/**
 * Every column is a fixed track — the amount and status ones were `auto`, so each sized to its own
 * row's content and a wide "Needs review" chip pushed that row's amount left. The figures stopped
 * sharing an edge and the columns stopped being columns.
 *
 * At shell width the amount ends on a common right edge (tabular-nums figures read down as one
 * block) and each chip starts on a common left edge. Below the breakpoint the row falls back to
 * the handoff's stacked two-column reading order, where those alignments do not apply.
 */
const ROW_CLASS =
  "grid grid-cols-2 gap-x-3 gap-y-1 border-t border-border/60 px-5 py-3 transition-colors hover:bg-muted/60 shell:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_7.5rem_7.5rem] shell:items-center shell:gap-3 shell:px-6";

interface RecentExpensesProps {
  expenses: Expense[];
  categoryName: (categoryId: string | null) => string;
}

export function RecentExpenses({ expenses, categoryName }: RecentExpensesProps) {
  return (
    <Card className="gap-0 py-1.5">
      <div className="flex items-center justify-between px-6 pt-3.5 pb-2.5">
        <div className="text-[12.5px] font-medium text-muted-foreground">Recent expenses</div>
        <Link href="/expenses" className="text-[12.5px] font-semibold text-primary">
          View all
        </Link>
      </div>
      {expenses.length === 0 ? (
        <div className="px-6 py-6 text-sm text-muted-foreground">No expenses yet.</div>
      ) : (
        expenses.map((expense) => (
          <Link
            key={expense.id}
            href={`/expenses/${expense.id}`}
            className={ROW_CLASS}
          >
            <div className="truncate text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
            <div data-testid="expense-category" className="truncate text-right text-[12.5px] text-muted-foreground shell:text-left">
              {categoryName(expense.categoryId)}
            </div>
            <div data-testid="expense-amount" className="font-mono text-[13px] tabular-nums shell:text-right">
              {formatMoney(expense.amountMinor, expense.currency)}
            </div>
            <div data-testid="expense-status" className="justify-self-end shell:justify-self-start"><StatusChip status={expense.status} /></div>
          </Link>
        ))
      )}
    </Card>
  );
}
