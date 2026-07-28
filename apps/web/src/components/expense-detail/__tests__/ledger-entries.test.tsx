import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LedgerEntries } from "@/components/expense-detail/ledger-entries";

describe("LedgerEntries", () => {
  it("renders a 'not posted yet' state instead of crashing when entries is empty (NEEDS_REVIEW)", () => {
    render(<LedgerEntries entries={[]} />);

    expect(screen.getByText(/Not posted yet/)).toBeInTheDocument();
  });

  it("shows the two entries and a zero balance for a balanced debit/credit pair", () => {
    render(
      <LedgerEntries
        entries={[
          {
            accountId: "acct-1",
            accountName: "Travel Expense",
            direction: "DEBIT",
            amountMinor: 234000,
            currency: "EUR",
          },
          {
            accountId: "acct-2",
            accountName: "Cash / Bank",
            direction: "CREDIT",
            amountMinor: 234000,
            currency: "EUR",
          },
        ]}
      />,
    );

    expect(screen.getByText("Travel Expense")).toBeInTheDocument();
    expect(screen.getByText("Cash / Bank")).toBeInTheDocument();
    expect(screen.getByTestId("ledger-balance")).toHaveTextContent("€0.00");
  });

  it("refuses to compute a single balance across mixed currencies rather than mislabeling one", () => {
    // A transaction balanced in base currency (fxRate applied) still has two different NATIVE
    // currencies here — LedgerEntryView projects the native amount/currency, not the base-
    // currency amount the zero-sum invariant actually holds over. Summing raw minor units across
    // USD and EUR would produce a fabricated non-zero "balance" in whichever currency came first.
    render(
      <LedgerEntries
        entries={[
          {
            accountId: "acct-1",
            accountName: "Travel Expense",
            direction: "DEBIT",
            amountMinor: 10000,
            currency: "USD",
          },
          {
            accountId: "acct-2",
            accountName: "Cash / Bank",
            direction: "CREDIT",
            amountMinor: 9200,
            currency: "EUR",
          },
        ]}
      />,
    );

    expect(screen.getByTestId("ledger-balance")).toHaveTextContent("Mixed currencies");
    expect(screen.queryByText("$8.00")).not.toBeInTheDocument();
  });
});
