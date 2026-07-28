import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SpendOverTimeChart } from "@/components/dashboard/spend-over-time-chart";

describe("SpendOverTimeChart", () => {
  it("renders an empty state instead of an empty chart when there is no history", () => {
    render(<SpendOverTimeChart series={[]} currency="USD" />);

    expect(screen.getByText(/No spend history yet/)).toBeInTheDocument();
  });

  it("gives the chart an accessible text alternative summarizing every month", () => {
    render(
      <SpendOverTimeChart
        series={[
          { month: "2026-06", amountMinor: 100000 },
          { month: "2026-07", amountMinor: 200000 },
        ]}
        currency="USD"
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
      <SpendOverTimeChart series={[{ month: "2026-07", amountMinor: 100000 }]} currency="USD" />,
    );

    expect(screen.getByRole("table", { name: "Monthly spend" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Jul" })).toBeInTheDocument();
  });

  it("zero-filled months render without NaN in the accessible summary", () => {
    render(
      <SpendOverTimeChart series={[{ month: "2026-07", amountMinor: 0 }]} currency="USD" />,
    );

    const chart = screen.getByRole("img");
    expect(chart.getAttribute("aria-label")).not.toContain("NaN");
    expect(chart.getAttribute("aria-label")).toContain("$0.00");
  });
});
