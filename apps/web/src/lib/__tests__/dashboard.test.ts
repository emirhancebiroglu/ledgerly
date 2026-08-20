import { describe, expect, it } from "vitest";
import { resolveDisplayCurrency, type DashboardSummary } from "@/lib/dashboard";

function summary(overrides: Partial<DashboardSummary>): DashboardSummary {
  return {
    totalsThisMonth: [],
    totalsLastMonth: [],
    categoryBreakdown: [],
    monthlySeries: [],
    reviewQueueCount: 0,
    documentsProcessedToday: 0,
    alertCount: 0,
    recentAlerts: [],
    ...overrides,
  };
}

describe("resolveDisplayCurrency", () => {
  it("prefers this month's currency when present", () => {
    const result = resolveDisplayCurrency(
      summary({
        totalsThisMonth: [{ currency: "EUR", amountMinor: 100 }],
        totalsLastMonth: [{ currency: "USD", amountMinor: 100 }],
      }),
    );

    expect(result).toBe("EUR");
  });

  it("falls back to last month's currency when this month has no spend yet", () => {
    // The exact edge case a naive `?? "USD"` default gets wrong: an org with a real EUR history
    // but nothing posted yet this month would otherwise have its category breakdown and spend
    // chart mislabeled in a currency that never appeared anywhere in its data.
    const result = resolveDisplayCurrency(
      summary({
        totalsThisMonth: [],
        totalsLastMonth: [{ currency: "EUR", amountMinor: 500000 }],
        categoryBreakdown: [{ categoryId: "1", categoryName: "Software", amountMinor: 500000 }],
      }),
    );

    expect(result).toBe("EUR");
  });

  it("returns undefined, not a fabricated default, when neither window has any data", () => {
    const result = resolveDisplayCurrency(summary({}));

    expect(result).toBeUndefined();
  });
});
