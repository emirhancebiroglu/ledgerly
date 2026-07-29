import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const sessionMocks = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setSessionCookies: vi.fn(),
  clearSessionCookies: vi.fn(),
}));
vi.mock("@/lib/session", () => sessionMocks);

function paramsFor(path: string[]) {
  return { params: Promise.resolve({ path }) };
}

describe("GET/POST /api/[...path]", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("returns 401 without ever reaching upstream when there is no session cookie", async () => {
    sessionMocks.getAccessToken.mockResolvedValue(undefined);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(
      new URL("http://localhost:3000/api/expenses?status=NEEDS_REVIEW"),
    );

    const response = await GET(request, paramsFor(["expenses"]));

    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards the joined path, query string, and upstream status/content-type", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ id: "1" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/expenses/1/detail"));

    const response = await GET(request, paramsFor(["expenses", "1", "detail"]));

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    const [calledUrl] = fetchMock.mock.calls[0];
    expect(calledUrl).toContain("/api/v1/expenses/1/detail");
  });

  it("rejects a path segment that attempts traversal outside /api/v1", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/..%2f..%2factuator"));

    const response = await GET(request, paramsFor(["..", "..", "actuator"]));

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a path segment containing a dot or percent-encoding", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/expenses/%2e%2e"));

    const response = await GET(request, paramsFor(["expenses", "%2e%2e"]));

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a cross-origin state-changing request before it reaches upstream", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/expenses/1/approve"), {
      method: "POST",
      headers: { origin: "https://evil.example" },
    });

    const response = await POST(request, paramsFor(["expenses", "1", "approve"]));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("allows a same-origin state-changing request through", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/expenses/1/approve"), {
      method: "POST",
      headers: { origin: "http://localhost:3000", host: "localhost:3000" },
    });

    const response = await POST(request, paramsFor(["expenses", "1", "approve"]));

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("allows a same-origin request through even when nextUrl's own origin differs from Host (standalone server bound to 0.0.0.0)", async () => {
    // Regression test: the standalone build (this app's actual deployment shape) binds 0.0.0.0
    // by default, so request.nextUrl.origin resolves to "http://0.0.0.0:<port>" rather than
    // whatever the client actually connected to — comparing Origin against nextUrl.origin 403'd
    // every legitimate same-origin POST in production while every test here passed, because
    // NextRequest built directly from a URL string doesn't reproduce that mismatch. The fix
    // compares Origin's host against the request's own Host header instead.
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    // Constructed the way the real server sees it: nextUrl reflects the bind address, but the
    // client's actual Host and Origin headers agree with each other.
    const request = new NextRequest(new URL("http://0.0.0.0:3100/api/expenses/1/approve"), {
      method: "POST",
      headers: { origin: "http://localhost:3100", host: "localhost:3100" },
    });

    const response = await POST(request, paramsFor(["expenses", "1", "approve"]));

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("still rejects a genuinely cross-origin request when Host and Origin actually disagree", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3100/api/expenses/1/approve"), {
      method: "POST",
      headers: { origin: "https://evil.example", host: "localhost:3100" },
    });

    const response = await POST(request, paramsFor(["expenses", "1", "approve"]));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("fails closed (403) when Origin is present but Host is missing, rather than allowing through", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3100/api/expenses/1/approve"), {
      method: "POST",
      headers: { origin: "http://localhost:3100" },
    });

    const response = await POST(request, paramsFor(["expenses", "1", "approve"]));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a request body larger than the API's 12MB multipart cap", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { POST } = await import("@/app/api/[...path]/route");
    const oversized = new Uint8Array(12 * 1024 * 1024 + 1);
    const request = new NextRequest(new URL("http://localhost:3000/api/documents"), {
      method: "POST",
      body: oversized,
    });

    const response = await POST(request, paramsFor(["documents"]));

    expect(response.status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards the SSE replay cursor and preserves event-stream response headers", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("token-1");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response("data: hello\n\n", {
        status: 200,
        headers: {
          "content-type": "text/event-stream",
          "cache-control": "no-cache",
          connection: "keep-alive",
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/documents/1/events"), {
      headers: { "last-event-id": "42" },
    });

    const response = await GET(request, paramsFor(["documents", "1", "events"]));

    expect(response.headers.get("content-type")).toBe("text/event-stream");
    expect(response.headers.get("cache-control")).toBe("no-cache");
    expect(response.headers.get("connection")).toBe("keep-alive");
    const [, upstreamInit] = fetchMock.mock.calls[0];
    expect(new Headers(upstreamInit.headers).get("last-event-id")).toBe("42");
  });
});
