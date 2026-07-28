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
});
