import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ExpenseList } from "@/components/expenses/expense-list";

const expense = {
  id: "1",
  documentId: "d1",
  vendor: "Acme Corp",
  categoryId: "cat-1",
  ledgerTransactionId: "txn-1",
  amountMinor: 12345,
  currency: "USD",
  categorizationConfidence: 0.9,
  citation: null,
  status: "POSTED" as const,
  createdAt: "2026-07-01T00:00:00Z",
};

describe("ExpenseList", () => {
  it("renders an empty state instead of a blank card when there are no results", () => {
    render(<ExpenseList expenses={[]} categoryName={() => "Uncategorized"} />);

    expect(screen.getByText("No expenses match these filters.")).toBeInTheDocument();
  });

  it("renders the server's error message instead of the empty state when the query is invalid", () => {
    render(
      <ExpenseList
        expenses={[]}
        categoryName={() => "Uncategorized"}
        errorMessage="Unknown sort field: bogus"
      />,
    );

    expect(screen.getByText("Unknown sort field: bogus")).toBeInTheDocument();
    expect(screen.queryByText("No expenses match these filters.")).not.toBeInTheDocument();
  });

  it("renders a row per expense with a resolved category name", () => {
    render(
      <ExpenseList expenses={[expense]} categoryName={(id) => (id === "cat-1" ? "Software" : "?")} />,
    );

    expect(screen.getByText("Acme Corp")).toBeInTheDocument();
    expect(screen.getByText("Software")).toBeInTheDocument();
    expect(screen.getByText("$123.45")).toBeInTheDocument();
  });
});
