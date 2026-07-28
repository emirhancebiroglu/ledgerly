import Link from "next/link";
import { Card } from "@/components/ui/card";
import { StatusChip } from "@/components/status-chip";
import { formatMoney } from "@/lib/money";
import type { Expense } from "@/lib/expenses";

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
            className="grid grid-cols-[1.5fr_1fr_auto_auto] items-center gap-3 border-t border-border/60 px-6 py-3 transition-colors hover:bg-muted/60"
          >
            <div className="truncate text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
            <div className="truncate text-[12.5px] text-muted-foreground">
              {categoryName(expense.categoryId)}
            </div>
            <div className="font-mono text-[13px] tabular-nums">
              {formatMoney(expense.amountMinor, expense.currency)}
            </div>
            <StatusChip status={expense.status} />
          </Link>
        ))
      )}
    </Card>
  );
}
