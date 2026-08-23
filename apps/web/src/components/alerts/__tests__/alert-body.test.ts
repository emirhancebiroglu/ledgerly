import { describe, expect, it } from "vitest";
import { alertBody } from "@/components/alerts/alert-body";
import { formatDateTime } from "@/lib/date";
import type { Alert } from "@/lib/alerts";

const BASE: Alert = {
  id: "alert-1",
  expenseId: "exp-1",
  categoryId: "cat-1",
  period: "2026-08",
  currency: "EUR",
  alertType: "DUPLICATE_SUSPECTED",
  thresholdPercent: null,
  spentMinor: null,
  limitMinor: null,
  historyCount: null,
  zScore: null,
  budgetBurnRate: null,
  explanation: null,
  model: null,
  createdAt: "2026-08-22T10:00:00Z",
  categorizationConfidence: null,
  matchedExpenseId: null,
  duplicateTier: null,
  matchedExpense: null,
  triggeringExpense: null,
  title: "",
  read: false,
  dismissed: false,
};

const categoryName = () => "Uncategorized";

describe("alertBody DUPLICATE_SUSPECTED", () => {
  it("states both entries' real figures with no rounding of minor units", () => {
    const alert: Alert = {
      ...BASE,
      duplicateTier: "CONFIRMED",
      matchedExpense: { vendor: "Office Depot", amountMinor: BigInt(12800), currency: "EUR", createdAt: "2026-07-12T09:00:00Z" },
      triggeringExpense: { vendor: "Office Depot", amountMinor: BigInt(89900), currency: "EUR", createdAt: "2026-07-21T07:00:00Z" },
    };

    expect(alertBody(alert, categoryName)).toBe(
      `This entry for €899.00 carries the same invoice number as an earlier entry for €128.00, posted ${formatDateTime("2026-07-12T09:00:00Z")}.`,
    );
  });

  it("uses a softer verb for a SUSPECTED (not CONFIRMED) tier", () => {
    const alert: Alert = {
      ...BASE,
      duplicateTier: "SUSPECTED",
      matchedExpense: { vendor: "Acme", amountMinor: BigInt(5000), currency: "EUR", createdAt: "2026-07-12T09:00:00Z" },
      triggeringExpense: { vendor: "Acme", amountMinor: BigInt(5000), currency: "EUR", createdAt: "2026-07-21T07:00:00Z" },
    };

    expect(alertBody(alert, categoryName)).toMatch(/closely matches/);
  });

  it("degrades to a readable sentence when the matched expense was deleted", () => {
    const alert: Alert = {
      ...BASE,
      duplicateTier: "CONFIRMED",
      matchedExpense: null,
      triggeringExpense: { vendor: "Office Depot", amountMinor: BigInt(89900), currency: "EUR", createdAt: "2026-07-21T07:00:00Z" },
    };

    expect(alertBody(alert, categoryName)).toBe(
      "This entry for €899.00 carries the same invoice number as an earlier entry that is no longer available to compare.",
    );
  });

  it("degrades to a readable sentence when neither expense is available", () => {
    const alert: Alert = { ...BASE, duplicateTier: "CONFIRMED", matchedExpense: null, triggeringExpense: null };

    expect(alertBody(alert, categoryName)).toBe(
      "This entry carries the same invoice number as an earlier entry that is no longer available to compare.",
    );
  });
});
