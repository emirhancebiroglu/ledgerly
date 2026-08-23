import { afterEach, describe, expect, it, vi } from "vitest";
import { dismissAlert, markAlertRead, markAllAlertsRead, parseAlert } from "@/lib/alerts";

describe("parseAlert", () => {
  it("parses a BUDGET_THRESHOLD payload including null optional fields", () => {
    const alert = parseAlert({
      id: "alert-1",
      expenseId: "exp-1",
      categoryId: "cat-1",
      period: "2026-08",
      currency: "EUR",
      alertType: "BUDGET_THRESHOLD",
      thresholdPercent: 100,
      spentMinor: "618000",
      limitMinor: "600000",
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
      title: "Marketing budget exceeded",
      read: false,
      dismissed: false,
    });

    expect(alert.spentMinor).toBe(BigInt(618000));
    expect(alert.limitMinor).toBe(BigInt(600000));
    expect(alert.historyCount).toBeNull();
    expect(alert.explanation).toBeNull();
  });

  it("parses an ANOMALY_HIGH payload with null spentMinor/limitMinor", () => {
    const alert = parseAlert({
      id: "alert-2",
      expenseId: "exp-2",
      categoryId: "cat-2",
      period: "2026-08",
      currency: "EUR",
      alertType: "ANOMALY_HIGH",
      thresholdPercent: null,
      spentMinor: null,
      limitMinor: null,
      historyCount: 12,
      zScore: 3.4,
      budgetBurnRate: 0.8,
      explanation: "Delta Airlines charged 845.00, well above this category's typical spend.",
      model: "gpt-test",
      createdAt: "2026-08-22T10:00:00Z",
      categorizationConfidence: null,
      matchedExpenseId: null,
      duplicateTier: null,
      matchedExpense: null,
      triggeringExpense: null,
      title: "Unusual spending detected",
      read: false,
      dismissed: false,
    });

    expect(alert.spentMinor).toBeNull();
    expect(alert.limitMinor).toBeNull();
    expect(alert.explanation).toMatch(/Delta Airlines/);
  });

  it("parses a DUPLICATE_SUSPECTED payload's matched and triggering expense summaries, converting amountMinor to bigint", () => {
    const alert = parseAlert({
      id: "alert-4",
      expenseId: "exp-2",
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
      matchedExpenseId: "exp-3",
      duplicateTier: "CONFIRMED",
      matchedExpense: { vendor: "Office Depot", amountMinor: "12800", currency: "EUR", createdAt: "2026-07-12T09:00:00Z" },
      triggeringExpense: { vendor: "Office Depot", amountMinor: "89900", currency: "EUR", createdAt: "2026-07-21T07:00:00Z" },
      title: "Office Depot may have been billed twice",
      read: false,
      dismissed: false,
    });

    expect(alert.matchedExpenseId).toBe("exp-3");
    expect(alert.duplicateTier).toBe("CONFIRMED");
    expect(alert.matchedExpense).toEqual({
      vendor: "Office Depot",
      amountMinor: BigInt(12800),
      currency: "EUR",
      createdAt: "2026-07-12T09:00:00Z",
    });
    expect(alert.triggeringExpense).toEqual({
      vendor: "Office Depot",
      amountMinor: BigInt(89900),
      currency: "EUR",
      createdAt: "2026-07-21T07:00:00Z",
    });
  });

  it("leaves matchedExpense null when the alert carries no matched expense", () => {
    const alert = parseAlert({
      id: "alert-5",
      expenseId: "exp-5",
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
      matchedExpenseId: "exp-9",
      duplicateTier: "SUSPECTED",
      matchedExpense: null,
      triggeringExpense: null,
      title: "Possible duplicate from Acme",
      read: false,
      dismissed: false,
    });

    expect(alert.matchedExpenseId).toBe("exp-9");
    expect(alert.matchedExpense).toBeNull();
    expect(alert.triggeringExpense).toBeNull();
  });

  it("rejects a malformed minor value rather than coercing it", () => {
    expect(() =>
      parseAlert({
        id: "alert-3",
        expenseId: "exp-3",
        categoryId: "cat-3",
        period: "2026-08",
        currency: "EUR",
        alertType: "BUDGET_THRESHOLD",
        thresholdPercent: 80,
        spentMinor: "not-a-number",
        limitMinor: "600000",
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
        title: "SaaS nearing its budget",
        read: false,
        dismissed: false,
      }),
    ).toThrow();
  });
});

describe("alert mutations", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("markAlertRead sends a fresh Idempotency-Key and returns ok:true on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await markAlertRead("alert-1");

    expect(result).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/alerts/alert-1/read");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  });

  it("dismissAlert returns a typed error result on failure instead of throwing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "Resource not found" }), { status: 404 }),
      ),
    );

    const result = await dismissAlert("alert-1");

    expect(result).toEqual({ ok: false, status: 404, message: "Resource not found" });
  });

  it("markAllAlertsRead returns a typed network-failure result rather than throwing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new Error("network down")),
    );

    const result = await markAllAlertsRead();

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.status).toBe(0);
    }
  });
});
