import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CategoryBreakdown } from "@/components/dashboard/category-breakdown";

describe("CategoryBreakdown", () => {
  it("renders an empty state instead of a blank card when there is no categorized spend", () => {
    render(<CategoryBreakdown categories={[]} />);

    expect(screen.getByText(/No categorized spend yet/)).toBeInTheDocument();
  });

  it("renders each category's amount and a progress bar sized to its share of total spend", () => {
    render(
      <CategoryBreakdown
        categories={[
          { categoryId: "1", categoryName: "Software", currency: "USD", amountMinor: 40000 },
          { categoryId: "2", categoryName: "Travel", currency: "USD", amountMinor: 20000 },
        ]}
      />,
    );

    expect(screen.getByText("Software")).toBeInTheDocument();
    expect(screen.getByText("$400.00")).toBeInTheDocument();

    const bars = screen.getAllByRole("progressbar");
    expect(bars[0]).toHaveAttribute("aria-valuenow", "67");
    expect(bars[1]).toHaveAttribute("aria-valuenow", "33");
  });

  it("renders one section per currency, each with its own symbol and its own 100% bar total", () => {
    render(
      <CategoryBreakdown
        categories={[
          { categoryId: "1", categoryName: "Software", currency: "TRY", amountMinor: 45000 },
          { categoryId: "2", categoryName: "Cloud", currency: "USD", amountMinor: 2000 },
        ]}
      />,
    );

    expect(screen.getByText("TRY")).toBeInTheDocument();
    expect(screen.getByText("USD")).toBeInTheDocument();
    // TRY's ₺450.00 and USD's $20.00 are each 100% of their own section -- neither figure is the
    // cross-currency sum of the two minor-unit amounts.
    expect(screen.getByText("₺450.00")).toBeInTheDocument();
    expect(screen.getByText("$20.00")).toBeInTheDocument();
    for (const bar of screen.getAllByRole("progressbar")) {
      expect(bar).toHaveAttribute("aria-valuenow", "100");
    }
  });

  it("renders no currency-grouping chrome for a single-currency org", () => {
    render(
      <CategoryBreakdown
        categories={[
          { categoryId: "1", categoryName: "Software", currency: "USD", amountMinor: 40000 },
        ]}
      />,
    );

    expect(screen.queryByText("USD")).not.toBeInTheDocument();
  });
});
