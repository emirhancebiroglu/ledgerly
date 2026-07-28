import Link from "next/link";
import { StatusChip } from "@/components/status-chip";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/date";
import type { Expense } from "@/lib/expenses";

interface ExpenseRowProps {
  expense: Expense;
  categoryName: (categoryId: string | null) => string;
}

export function ExpenseRow({ expense, categoryName }: ExpenseRowProps) {
  return (
    <Link
      href={`/expenses/${expense.id}`}
      className="grid grid-cols-1 gap-1 border-b border-border/60 px-5 py-3 transition-colors last:border-b-0 hover:bg-muted/60 shell:grid-cols-[1.5fr_1fr_1fr_1fr_1fr] shell:items-center shell:gap-4"
    >
      <div className="truncate text-[13px] font-medium">{expense.vendor ?? "Unknown vendor"}</div>
      <div className="truncate text-[12.5px] text-muted-foreground">
        {categoryName(expense.categoryId)}
      </div>
      <div className="font-mono text-[12.5px] text-muted-foreground">
        {formatDate(expense.createdAt)}
      </div>
      <div className="font-mono text-[13px] tabular-nums">
        {formatMoney(expense.amountMinor, expense.currency)}
      </div>
      <div>
        <StatusChip status={expense.status} />
      </div>
    </Link>
  );
}
