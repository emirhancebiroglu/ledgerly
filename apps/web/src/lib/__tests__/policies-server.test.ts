import { afterEach, describe, expect, it, vi } from "vitest";

const sessionMocks = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setSessionCookies: vi.fn(),
  clearSessionCookies: vi.fn(),
}));
vi.mock("@/lib/session", () => sessionMocks);

describe("listPolicyDocuments", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("returns ok:true with the parsed documents on a 200", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify([
            { id: "1", filename: "a.pdf", status: "EMBEDDED", createdAt: "t", chunkCount: 3 },
          ]),
          { status: 200 },
        ),
      ),
    );

    const { listPolicyDocuments } = await import("@/lib/policies-server");
    const result = await listPolicyDocuments();

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.documents).toHaveLength(1);
    }
  });

  it("returns a not-ok result on an API failure without throwing", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("boom", { status: 500 })));

    const { listPolicyDocuments } = await import("@/lib/policies-server");
    const result = await listPolicyDocuments();

    expect(result).toEqual({ ok: false });
  });

  it("returns a not-ok result when the response body fails to parse", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: "1" }]), { status: 200 })),
    );

    const { listPolicyDocuments } = await import("@/lib/policies-server");
    const result = await listPolicyDocuments();

    expect(result).toEqual({ ok: false });
  });

  it("returns a not-ok result when unauthenticated rather than throwing", async () => {
    sessionMocks.getAccessToken.mockResolvedValue(null);

    const { listPolicyDocuments } = await import("@/lib/policies-server");
    const result = await listPolicyDocuments();

    expect(result).toEqual({ ok: false });
  });
});

describe("getPolicyDocument", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("returns ok:true with the parsed document on a 200", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ id: "1", filename: "a.pdf", status: "FAILED", failureReason: "x", createdAt: "t", chunkCount: 0 }),
          { status: 200 },
        ),
      ),
    );

    const { getPolicyDocument } = await import("@/lib/policies-server");
    const result = await getPolicyDocument("1");

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.document.status).toBe("FAILED");
    }
  });

  it("surfaces a 404 status rather than throwing", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 404 })));

    const { getPolicyDocument } = await import("@/lib/policies-server");
    const result = await getPolicyDocument("missing");

    expect(result).toEqual({ ok: false, status: 404 });
  });
});

describe("listPolicyChunks", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("returns all chunks from a single page", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify([{ index: 0, text: "a" }, { index: 1, text: "b" }]), {
          status: 200,
        }),
      ),
    );

    const { listPolicyChunks } = await import("@/lib/policies-server");
    const result = await listPolicyChunks("1");

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.chunks).toHaveLength(2);
    }
  });

  it("concatenates a full page followed by a partial page", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fullPage = Array.from({ length: 200 }, (_, i) => ({ index: i, text: `chunk ${i}` }));
    const partialPage = [{ index: 200, text: "last" }];
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(fullPage), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(partialPage), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { listPolicyChunks } = await import("@/lib/policies-server");
    const result = await listPolicyChunks("1");

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.chunks).toHaveLength(201);
      expect(result.chunks[200]).toEqual({ index: 200, text: "last" });
    }
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("returns a not-ok result with the status on a failed page fetch", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 404 })));

    const { listPolicyChunks } = await import("@/lib/policies-server");
    const result = await listPolicyChunks("missing");

    expect(result).toEqual({ ok: false, status: 404 });
  });
});
