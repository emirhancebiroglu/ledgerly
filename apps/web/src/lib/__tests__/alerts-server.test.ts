import { afterEach, describe, expect, it, vi } from "vitest";

const apiServerMocks = vi.hoisted(() => ({
  apiFetchAuthenticated: vi.fn(),
}));

vi.mock("@/lib/api-server", () => apiServerMocks);

async function importAlertsServer() {
  return import("@/lib/alerts-server");
}

describe("listAlerts", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
    apiServerMocks.apiFetchAuthenticated.mockReset();
  });

  it("returns a not-ok result on an API failure without throwing", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(new Response(null, { status: 500 }));

    const { listAlerts } = await importAlertsServer();
    const result = await listAlerts();

    expect(result).toEqual({ ok: false });
  });

  it("parses a successful page of alerts and includes the type filter in the query", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(
      new Response(
        JSON.stringify([
          {
            id: "alert-1",
            expenseId: "exp-1",
            categoryId: "cat-1",
            period: "2026-08",
            currency: "EUR",
            alertType: "LOW_CONFIDENCE",
            thresholdPercent: null,
            spentMinor: null,
            limitMinor: null,
            historyCount: null,
            zScore: null,
            budgetBurnRate: null,
            explanation: null,
            model: null,
            createdAt: "2026-08-22T10:00:00Z",
            categorizationConfidence: 0.42,
            title: "Low-confidence categorization needs review",
            read: false,
            dismissed: false,
          },
        ]),
        { status: 200 },
      ),
    );

    const { listAlerts } = await importAlertsServer();
    const result = await listAlerts({ type: "LOW_CONFIDENCE" });

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.alerts).toHaveLength(1);
      expect(result.alerts[0].alertType).toBe("LOW_CONFIDENCE");
      expect(result.alerts[0].categorizationConfidence).toBe(0.42);
    }
    const [path] = apiServerMocks.apiFetchAuthenticated.mock.calls[0];
    expect(path).toContain("type=LOW_CONFIDENCE");
  });
});

describe("unreadAlertCount", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
    apiServerMocks.apiFetchAuthenticated.mockReset();
  });

  it("returns 0 on an API failure rather than throwing", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(new Response(null, { status: 500 }));

    const { unreadAlertCount } = await importAlertsServer();
    const count = await unreadAlertCount();

    expect(count).toBe(0);
  });

  it("returns the parsed unread count on success", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(
      new Response(JSON.stringify({ unreadCount: 3 }), { status: 200 }),
    );

    const { unreadAlertCount } = await importAlertsServer();
    const count = await unreadAlertCount();

    expect(count).toBe(3);
  });
});
