import { describe, expect, it } from "vitest";
import { formatMoney } from "@/lib/money";

describe("formatMoney", () => {
  it("converts minor units to a formatted major-unit string", () => {
    expect(formatMoney(123456, "USD")).toBe("$1,234.56");
  });

  it("handles zero without a stray -0 or NaN", () => {
    expect(formatMoney(0, "USD")).toBe("$0.00");
  });

  it("handles a single-cent amount", () => {
    expect(formatMoney(1, "USD")).toBe("$0.01");
  });

  it("formats a non-USD currency with its own symbol", () => {
    expect(formatMoney(123456, "EUR")).toBe("€1,234.56");
  });

  it("never introduces float rounding drift on a value binary floats can't represent exactly", () => {
    // 8455 minor units is $84.55 — 84.55 has no exact binary float representation, the classic
    // case that silently corrupts a naive `amountMinor / 100` display if anything downstream
    // does further math on the float instead of the original integer.
    expect(formatMoney(8455, "USD")).toBe("$84.55");
  });

  // `currencyDisplay: "symbol"` leaves TRY as the literal code ("TRY 1,234.56") under the pinned
  // `en-US` locale, which read as an inconsistency next to "$101,237.50" in the same list.
  // Only "narrowSymbol" resolves every supported currency to an actual symbol.
  it("renders every supported currency as a symbol rather than a code", () => {
    expect(formatMoney(123456, "TRY")).toBe("₺1,234.56");
    expect(formatMoney(123456, "GBP")).toBe("£1,234.56");
  });

  it("keeps the bigint path's symbol and separators identical to the number path", () => {
    expect(formatMoney(BigInt(123456), "TRY")).toBe("₺1,234.56");
    expect(formatMoney(BigInt(123456), "USD")).toBe("$1,234.56");
  });

  it("formats an amount beyond Number.MAX_SAFE_INTEGER without precision loss", () => {
    // 9007199254740993 is MAX_SAFE_INTEGER + 2 — unrepresentable as a `number`, so this can only
    // pass on the integer-preserving bigint path.
    expect(formatMoney(BigInt("9007199254740993"), "TRY")).toBe("₺90,071,992,547,409.93");
  });

  it("renders a credit note's negative amount with a leading sign and no double negative", () => {
    expect(formatMoney(-123456, "TRY")).toBe("-₺1,234.56");
    expect(formatMoney(BigInt(-123456), "TRY")).toBe("-₺1,234.56");
  });

  it("renders a zero bigint amount without a stray -0", () => {
    expect(formatMoney(BigInt(0), "TRY")).toBe("₺0.00");
  });
});
