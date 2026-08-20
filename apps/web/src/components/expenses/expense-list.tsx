import { Card } from "@/components/ui/card";
import { EXPENSE_GRID_TEMPLATE, ExpenseRow } from "@/components/expenses/expense-row";
import type { Expense } from "@/lib/expenses";

const COLUMN_HEADERS = ["Vendor", "Category", "Date", "Amount", "Status"];

interface ExpenseListProps {
  expenses: Expense[];
  categoryName: (categoryId: string | null) => string;
  errorMessage?: string;
}

export function ExpenseList({ expenses, categoryName, errorMessage }: ExpenseListProps) {
  if (errorMessage) {
    return (
      <Card className="p-8 text-center text-sm text-destructive">
        {errorMessage}
      </Card>
    );
  }

  if (expenses.length === 0) {
    return (
      <Card className="p-8 text-center text-sm text-muted-foreground">
        No expenses match these filters.
      </Card>
    );
  }

  return (
    <Card className="gap-0 overflow-hidden py-0">
      <div
        className={`hidden border-b border-border px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-muted-foreground uppercase shell:grid ${EXPENSE_GRID_TEMPLATE}`}
      >
        {COLUMN_HEADERS.map((header) => (
          // "Amount" sits over right-aligned figures, so the label follows them to that edge.
          <div key={header} className={header === "Amount" ? "text-right" : undefined}>
            {header}
          </div>
        ))}
      </div>
      {expenses.map((expense) => (
        <ExpenseRow key={expense.id} expense={expense} categoryName={categoryName} />
      ))}
    </Card>
  );
}
