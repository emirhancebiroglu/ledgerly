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

  // LedgerEntryView projects the native amount/currency each entry actually posted in — the
  // zero-sum invariant a transaction guarantees is over the *base*-currency amount, a different
  // column this view doesn't carry. Today every entry's native currency always equals its base
  // currency (ExpensePostingTransactions posts with fxRate = 1), so summing native units is
  // equivalent — but FX support already exists as an API shape (fxRate, CurrencyMismatchException)
  // for exactly the case where that stops being true. Detecting a currency mismatch here and
  // refusing to compute a single balance is safer than silently adding incommensurable units and
  // labeling the result with whichever currency happened to come first.
  const currencies = new Set(entries.map((entry) => entry.currency));
  const isSingleCurrency = currencies.size === 1;
  const balance = isSingleCurrency
    ? entries.reduce((sum, entry) => sum + signedAmount(entry), 0)
    : null;
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
        {isSingleCurrency ? (
          <span
            className="font-mono tabular-nums"
            data-testid="ledger-balance"
            aria-label={`Ledger balance: ${formatMoney(balance!, currency)}`}
          >
            {formatMoney(balance!, currency)}
          </span>
        ) : (
          <span
            className="text-muted-foreground"
            data-testid="ledger-balance"
            title="Entries post in more than one currency — no single balance to show."
          >
            Mixed currencies
          </span>
        )}
      </div>
    </Card>
  );
}
