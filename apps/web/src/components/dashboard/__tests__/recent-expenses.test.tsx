import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RecentExpenses } from "@/components/dashboard/recent-expenses";

describe("RecentExpenses", () => {
  it("renders an empty state instead of a blank card when there are no expenses", () => {
    render(<RecentExpenses expenses={[]} categoryName={() => "Uncategorized"} />);

    expect(screen.getByText(/No expenses yet/)).toBeInTheDocument();
  });

  it("renders a fallback for a null vendor rather than a blank row", () => {
    render(
      <RecentExpenses
        expenses={[
          {
            id: "1",
            documentId: "d1",
            vendor: null,
            categoryId: null,
            ledgerTransactionId: null,
            amountMinor: 1000,
            currency: "USD",
            categorizationConfidence: 0.5,
            citation: null,
            status: "NEEDS_REVIEW",
            createdAt: "2026-01-01T00:00:00Z",
          },
        ]}
        categoryName={() => "Uncategorized"}
      />,
    );

    expect(screen.getByText("Unknown vendor")).toBeInTheDocument();
  });
});
