import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CategoryBreakdown } from "@/components/dashboard/category-breakdown";

describe("CategoryBreakdown", () => {
  it("renders an empty state instead of a blank card when there is no categorized spend", () => {
    render(<CategoryBreakdown categories={[]} currency="USD" />);

    expect(screen.getByText(/No categorized spend yet/)).toBeInTheDocument();
  });

  it("renders each category's amount and a progress bar sized to its share of total spend", () => {
    render(
      <CategoryBreakdown
        categories={[
          { categoryId: "1", categoryName: "Software", amountMinor: 40000 },
          { categoryId: "2", categoryName: "Travel", amountMinor: 20000 },
        ]}
        currency="USD"
      />,
    );

    expect(screen.getByText("Software")).toBeInTheDocument();
    expect(screen.getByText("$400.00")).toBeInTheDocument();

    const bars = screen.getAllByRole("progressbar");
    expect(bars[0]).toHaveAttribute("aria-valuenow", "67");
    expect(bars[1]).toHaveAttribute("aria-valuenow", "33");
  });
});
