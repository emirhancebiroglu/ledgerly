import Link from "next/link";
import { StatusChip } from "@/components/status-chip";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/date";
import type { Expense } from "@/lib/expenses";

/**
 * Same alignment contract as the dashboard's recent-expenses rows: amount and status are fixed
 * tracks so neither follows its own content width, keeping the two lists visually consistent.
 *
 * Exported because the list's column headers must use the identical template — a header on its own
 * copy of the tracks silently stops labelling the column beneath it the moment either one changes.
 */
export const EXPENSE_GRID_TEMPLATE =
  "shell:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1fr)_7.5rem_7.5rem] shell:gap-4";

const EXPENSE_ROW_CLASS =
  `grid grid-cols-1 gap-1 border-b border-border/60 px-5 py-3 transition-colors last:border-b-0 hover:bg-muted/60 shell:items-center ${EXPENSE_GRID_TEMPLATE}`;

interface ExpenseRowProps {
  expense: Expense;
  categoryName: (categoryId: string | null) => string;
}

export function ExpenseRow({ expense, categoryName }: ExpenseRowProps) {
  return (
    <Link
      href={`/expenses/${expense.id}`}
      className={EXPENSE_ROW_CLASS}
    >
      <div className="truncate text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
      <div data-testid="expense-category" className="truncate text-[12.5px] text-muted-foreground">
        {categoryName(expense.categoryId)}
      </div>
      <div className="truncate font-mono text-[12.5px] text-muted-foreground">
        {formatDate(expense.createdAt)}
      </div>
      <div data-testid="expense-amount" className="font-mono text-[13px] tabular-nums shell:text-right">
        {formatMoney(expense.amountMinor, expense.currency)}
      </div>
      <div data-testid="expense-status">
        <StatusChip status={expense.status} />
      </div>
    </Link>
  );
}
