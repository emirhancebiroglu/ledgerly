import { afterEach, describe, expect, it, vi } from "vitest";
import { approveExpense, correctExpense } from "@/lib/expense-actions";

describe("approveExpense", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("sends a fresh Idempotency-Key and returns ok:true on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await approveExpense("exp-1");

    expect(result).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/expenses/exp-1/approve");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  });

  it("reports a 409 (already resolved) rather than swallowing it", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "This expense has already been resolved" }), {
          status: 409,
        }),
      ),
    );

    const result = await approveExpense("exp-7");

    expect(result).toEqual({
      ok: false,
      status: 409,
      message: "This expense has already been resolved",
    });
  });

  it("falls back to a 409-specific message when the error body isn't JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 409 })));

    const result = await approveExpense("exp-7");

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.message).toMatch(/already resolved/);
    }
  });

  it("two calls generate two different idempotency keys", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await approveExpense("exp-1");
    await approveExpense("exp-2");

    const key1 = (fetchMock.mock.calls[0][1].headers as Record<string, string>)["Idempotency-Key"];
    const key2 = (fetchMock.mock.calls[1][1].headers as Record<string, string>)["Idempotency-Key"];
    expect(key1).not.toBe(key2);
  });
});

describe("correctExpense", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("sends the categoryId as a JSON body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await correctExpense("exp-1", "cat-2");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/expenses/exp-1/correct");
    expect(JSON.parse(init.body as string)).toEqual({ categoryId: "cat-2" });
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});
