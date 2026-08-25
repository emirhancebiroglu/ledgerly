import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { KpiCard } from "@/components/dashboard/kpi-card";

describe("KpiCard", () => {
  it("renders an empty state instead of NaN or a bare $0.00 when there is no spend", () => {
    render(<KpiCard totalsThisMonth={[]} totalsLastMonth={[]} monthlySeries={[]} />);

    expect(screen.getByText(/No expenses posted yet/)).toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it("renders each currency separately rather than summing them", () => {
    render(
      <KpiCard
        totalsThisMonth={[
          { currency: "EUR", amountMinor: 123456 },
          { currency: "USD", amountMinor: 50000 },
        ]}
        totalsLastMonth={[]}
        monthlySeries={[]}
      />,
    );

    expect(screen.getByText("€1,234.56")).toBeInTheDocument();
    expect(screen.getByText("$500.00")).toBeInTheDocument();
  });

  it("gives each currency's sparkline only that currency's own months, not another's history", () => {
    render(
      <KpiCard
        totalsThisMonth={[
          { currency: "EUR", amountMinor: 1000 },
          { currency: "USD", amountMinor: 2000 },
        ]}
        totalsLastMonth={[]}
        monthlySeries={[
          { month: "2026-06", currency: "EUR", amountMinor: 500 },
          { month: "2026-07", currency: "EUR", amountMinor: 1000 },
          { month: "2026-06", currency: "USD", amountMinor: 100 },
          { month: "2026-07", currency: "USD", amountMinor: 200 },
          { month: "2026-05", currency: "USD", amountMinor: 300 },
        ]}
      />,
    );

    const eurTrend = screen.getByLabelText("Spend trend over the trailing 2 months");
    const usdTrend = screen.getByLabelText("Spend trend over the trailing 3 months");
    expect(eurTrend).toBeInTheDocument();
    expect(usdTrend).toBeInTheDocument();
  });

  it("shows an up arrow and green delta when spend increased vs last month", () => {
    render(
      <KpiCard
        totalsThisMonth={[{ currency: "USD", amountMinor: 20000 }]}
        totalsLastMonth={[{ currency: "USD", amountMinor: 10000 }]}
        monthlySeries={[]}
      />,
    );

    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.getByLabelText(/Up 100% vs last month/)).toBeInTheDocument();
  });

  it("shows a down arrow when spend decreased vs last month", () => {
    render(
      <KpiCard
        totalsThisMonth={[{ currency: "USD", amountMinor: 5000 }]}
        totalsLastMonth={[{ currency: "USD", amountMinor: 10000 }]}
        monthlySeries={[]}
      />,
    );

    expect(screen.getByLabelText(/Down 50% vs last month/)).toBeInTheDocument();
  });

  it("does not claim a comparison when there was no spend last month", () => {
    render(
      <KpiCard
        totalsThisMonth={[{ currency: "USD", amountMinor: 5000 }]}
        totalsLastMonth={[]}
        monthlySeries={[]}
      />,
    );

    expect(screen.getByText(/No spend last month to compare against/)).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });
});
