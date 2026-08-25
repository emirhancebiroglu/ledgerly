import { describe, expect, it } from "vitest";
import { groupByCurrency } from "@/lib/dashboard";

describe("groupByCurrency", () => {
  it("returns an empty list for an empty input", () => {
    expect(groupByCurrency([])).toEqual([]);
  });

  it("returns a single section for a single-currency list", () => {
    const rows = [
      { currency: "EUR", amountMinor: 100 },
      { currency: "EUR", amountMinor: 200 },
    ];

    expect(groupByCurrency(rows)).toEqual([["EUR", rows]]);
  });

  it("splits rows into one array per currency, preserving each row's own order", () => {
    const eur1 = { currency: "EUR", amountMinor: 100 };
    const usd1 = { currency: "USD", amountMinor: 200 };
    const eur2 = { currency: "EUR", amountMinor: 300 };

    const result = groupByCurrency([eur1, usd1, eur2]);

    expect(result).toEqual([
      ["EUR", [eur1, eur2]],
      ["USD", [usd1]],
    ]);
  });

  it("sorts sections by currency code for a deterministic render order", () => {
    const rows = [
      { currency: "USD", amountMinor: 1 },
      { currency: "TRY", amountMinor: 2 },
      { currency: "EUR", amountMinor: 3 },
    ];

    const result = groupByCurrency(rows);

    expect(result.map(([currency]) => currency)).toEqual(["EUR", "TRY", "USD"]);
  });
});
