import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const apiServerMocks = vi.hoisted(() => ({
  apiFetchAuthenticated: vi.fn(),
}));
vi.mock("@/lib/api-server", () => apiServerMocks);

describe("GET /api/[...path]", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
  });

  it("returns 401 without ever reaching upstream when there is no session", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(new Response(null, { status: 401 }));

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(
      new URL("http://localhost:3000/api/expenses?status=NEEDS_REVIEW"),
    );

    const response = await GET(request, { params: Promise.resolve({ path: ["expenses"] }) });

    expect(response.status).toBe(401);
    expect(apiServerMocks.apiFetchAuthenticated).toHaveBeenCalledWith(
      "/api/v1/expenses?status=NEEDS_REVIEW",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("forwards the joined path, query string, and upstream status/content-type", async () => {
    apiServerMocks.apiFetchAuthenticated.mockResolvedValue(
      new Response(JSON.stringify({ id: "1" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const { GET } = await import("@/app/api/[...path]/route");
    const request = new NextRequest(new URL("http://localhost:3000/api/expenses/1/detail"));

    const response = await GET(request, {
      params: Promise.resolve({ path: ["expenses", "1", "detail"] }),
    });

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(apiServerMocks.apiFetchAuthenticated).toHaveBeenCalledWith(
      "/api/v1/expenses/1/detail",
      expect.anything(),
    );
  });
});
