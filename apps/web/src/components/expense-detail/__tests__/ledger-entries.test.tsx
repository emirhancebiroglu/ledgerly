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
});
