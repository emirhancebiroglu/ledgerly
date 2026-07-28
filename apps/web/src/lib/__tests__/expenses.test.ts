import { afterEach, describe, expect, it, vi } from "vitest";

const sessionMocks = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setSessionCookies: vi.fn(),
  clearSessionCookies: vi.fn(),
}));
vi.mock("@/lib/session", () => sessionMocks);

describe("listExpenses", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("returns ok:true with the parsed array on a 200", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: "1" }]), { status: 200 })),
    );

    const { listExpenses } = await import("@/lib/expenses");
    const result = await listExpenses();

    expect(result).toEqual({ ok: true, expenses: [{ id: "1" }] });
  });

  it("returns ok:false with the server's own message on a 400 (bad query)", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "Unknown sort field: bogus" }), { status: 400 }),
      ),
    );

    const { listExpenses } = await import("@/lib/expenses");
    const result = await listExpenses({ sort: "bogus,desc" });

    expect(result).toEqual({
      ok: false,
      status: 400,
      message: "Unknown sort field: bogus",
    });
  });

  it("falls back to a generic message when the error body isn't JSON", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("boom", { status: 500 })));

    const { listExpenses } = await import("@/lib/expenses");
    const result = await listExpenses();

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.status).toBe(500);
      expect(result.message).toMatch(/Something went wrong/);
    }
  });

  it("builds the exact sort parameter form the API expects", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn().mockResolvedValue(new Response("[]", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { listExpenses } = await import("@/lib/expenses");
    await listExpenses({ sort: "amount,desc" });

    const [calledUrl] = fetchMock.mock.calls[0];
    expect(calledUrl).toContain("sort=amount%2Cdesc");
  });
});
