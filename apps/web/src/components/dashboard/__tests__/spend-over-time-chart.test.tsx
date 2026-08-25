import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SpendOverTimeChart } from "@/components/dashboard/spend-over-time-chart";

describe("SpendOverTimeChart", () => {
  it("renders an empty state instead of an empty chart when there is no history", () => {
    render(<SpendOverTimeChart series={[]} />);

    expect(screen.getByText(/No spend history yet/)).toBeInTheDocument();
  });

  it("gives the chart an accessible text alternative summarizing every month", () => {
    render(
      <SpendOverTimeChart
        series={[
          { month: "2026-06", currency: "USD", amountMinor: 100000 },
          { month: "2026-07", currency: "USD", amountMinor: 200000 },
        ]}
      />,
    );

    const chart = screen.getByRole("img");
    expect(chart.getAttribute("aria-label")).toContain("Jun");
    expect(chart.getAttribute("aria-label")).toContain("Jul");
    expect(chart.getAttribute("aria-label")).toContain("$1,000.00");
    expect(chart.getAttribute("aria-label")).toContain("$2,000.00");
  });

  it("also renders a real (visually hidden) data table, not just an aria-label", () => {
    render(
      <SpendOverTimeChart series={[{ month: "2026-07", currency: "USD", amountMinor: 100000 }]} />,
    );

    expect(screen.getByRole("table", { name: "Monthly spend" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Jul" })).toBeInTheDocument();
  });

  it("zero-filled months render without NaN in the accessible summary", () => {
    render(<SpendOverTimeChart series={[{ month: "2026-07", currency: "USD", amountMinor: 0 }]} />);

    const chart = screen.getByRole("img");
    expect(chart.getAttribute("aria-label")).not.toContain("NaN");
    expect(chart.getAttribute("aria-label")).toContain("$0.00");
  });

  it("renders one complete series per currency, never a summed point per month", () => {
    render(
      <SpendOverTimeChart
        series={[
          { month: "2026-07", currency: "EUR", amountMinor: 1000 },
          { month: "2026-07", currency: "USD", amountMinor: 2000 },
        ]}
      />,
    );

    const charts = screen.getAllByRole("img");
    expect(charts).toHaveLength(2);
    const labels = charts.map((chart) => chart.getAttribute("aria-label") ?? "");
    expect(labels.some((label) => label.includes("€10.00"))).toBe(true);
    expect(labels.some((label) => label.includes("$20.00"))).toBe(true);
    // Neither currency's chart claims the other's amount, and no chart shows a summed figure.
    expect(labels.every((label) => !label.includes("30.00"))).toBe(true);
  });

  it("renders no currency-grouping chrome for a single-currency org", () => {
    render(
      <SpendOverTimeChart series={[{ month: "2026-07", currency: "USD", amountMinor: 100000 }]} />,
    );

    expect(screen.queryByText("USD")).not.toBeInTheDocument();
  });
});
