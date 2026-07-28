import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import type { LedgerEntryView } from "@/lib/expense-detail";

interface LedgerEntriesProps {
  entries: LedgerEntryView[];
}

function signedAmount(entry: LedgerEntryView): number {
  return entry.direction === "DEBIT" ? entry.amountMinor : -entry.amountMinor;
}

export function LedgerEntries({ entries }: LedgerEntriesProps) {
  if (entries.length === 0) {
    return (
      <Card className="p-[22px_24px]">
        <div className="mb-1 text-[12.5px] font-medium text-muted-foreground">
          Ledger entries
        </div>
        <div className="text-sm text-muted-foreground">
          Not posted yet — this expense is still in the review queue.
        </div>
      </Card>
    );
  }

  const balance = entries.reduce((sum, entry) => sum + signedAmount(entry), 0);
  const currency = entries[0].currency;

  return (
    <Card className="p-[22px_24px]">
      <div className="mb-3 text-[12.5px] font-medium text-muted-foreground">Ledger entries</div>
      <div className="flex flex-col gap-2.5">
        {entries.map((entry, index) => (
          <div
            key={`${entry.accountId}-${entry.direction}-${index}`}
            className="flex items-center justify-between gap-3"
          >
            <div className="min-w-0">
              <div className="font-mono text-[12.5px] text-muted-foreground">
                {entry.accountId.slice(0, 8)}
              </div>
              <div className="truncate text-[13px] font-medium">{entry.accountName}</div>
            </div>
            <div className="shrink-0 text-right">
              <div className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
                {entry.direction}
              </div>
              <div className="font-mono text-[13px] tabular-nums">
                {formatMoney(entry.amountMinor, entry.currency)}
              </div>
            </div>
          </div>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-between border-t border-border pt-3 text-[12.5px]">
        <span className="text-muted-foreground">Balance</span>
        <span
          className="font-mono tabular-nums"
          data-testid="ledger-balance"
          aria-label={`Ledger balance: ${formatMoney(balance, currency)}`}
        >
          {formatMoney(balance, currency)}
        </span>
      </div>
    </Card>
  );
}
