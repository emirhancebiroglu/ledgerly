import { Card } from "@/components/ui/card";
import { StatusChip } from "@/components/status-chip";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/date";
import type { ExpenseDetail } from "@/lib/expense-detail";

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-[11.5px] text-muted-foreground">{label}</div>
      <div className="mt-0.5 font-mono text-[13px] tabular-nums">{value}</div>
    </div>
  );
}

interface ExtractedFieldsProps {
  expense: ExpenseDetail;
}

export function ExtractedFields({ expense }: ExtractedFieldsProps) {
  return (
    <Card className="p-[22px_24px]">
      <div className="mb-4 flex items-center justify-between">
        <div className="text-[15px] font-semibold">{expense.vendor ?? "Unknown vendor"}</div>
        <StatusChip status={expense.status} />
      </div>
      <div className="grid grid-cols-2 gap-x-4 gap-y-4">
        <Field label="Vendor" value={expense.vendor ?? "—"} />
        <Field label="Date" value={formatDate(expense.createdAt)} />
        <Field label="Amount" value={formatMoney(expense.amountMinor, expense.currency)} />
        <Field
          label="Confidence"
          value={`${Math.round(expense.categorizationConfidence * 100)}%`}
        />
      </div>
    </Card>
  );
}
