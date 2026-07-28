import { afterEach, describe, expect, it, vi } from "vitest";

const sessionMocks = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setSessionCookies: vi.fn(),
  clearSessionCookies: vi.fn(),
}));

vi.mock("@/lib/session", () => sessionMocks);

async function importApiServer() {
  return import("@/lib/api-server");
}

describe("apiFetchAuthenticated", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("attaches the bearer token from the session and returns on success", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("access-1");
    const fetchMock = vi.fn().mockResolvedValue(new Response("ok", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { apiFetchAuthenticated } = await importApiServer();
    const response = await apiFetchAuthenticated("/api/v1/expenses");

    expect(response.status).toBe(200);
    const [, init] = fetchMock.mock.calls[0];
    expect((init.headers as Headers).get("Authorization")).toBe("Bearer access-1");
  });

  it("returns 401 immediately when there is no session cookie at all", async () => {
    sessionMocks.getAccessToken.mockResolvedValue(undefined);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { apiFetchAuthenticated } = await importApiServer();
    const response = await apiFetchAuthenticated("/api/v1/expenses");

    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refreshes exactly once on a 401 and retries with the new token", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("expired-token");
    sessionMocks.getRefreshToken.mockResolvedValue("refresh-1");

    const fetchMock = vi
      .fn()
      // first call to the protected resource: 401
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      // refresh call: succeeds
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ accessToken: "fresh-token", refreshToken: "refresh-2" }), {
          status: 200,
        }),
      )
      // retry of the protected resource with the fresh token: succeeds
      .mockResolvedValueOnce(new Response("ok", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const { apiFetchAuthenticated } = await importApiServer();
    const response = await apiFetchAuthenticated("/api/v1/expenses");

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(sessionMocks.setSessionCookies).toHaveBeenCalledWith({
      accessToken: "fresh-token",
      refreshToken: "refresh-2",
    });
    const retryInit = fetchMock.mock.calls[2][1];
    expect((retryInit.headers as Headers).get("Authorization")).toBe("Bearer fresh-token");
  });

  it("does not loop when the refreshed token also gets a 401", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("expired-token");
    sessionMocks.getRefreshToken.mockResolvedValue("refresh-1");

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ accessToken: "fresh-token", refreshToken: "refresh-2" }), {
          status: 200,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    const { apiFetchAuthenticated } = await importApiServer();
    const response = await apiFetchAuthenticated("/api/v1/expenses");

    expect(response.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("clears the session and returns the original 401 when the refresh token is invalid", async () => {
    sessionMocks.getAccessToken.mockResolvedValue("expired-token");
    sessionMocks.getRefreshToken.mockResolvedValue("stale-refresh");

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    const { apiFetchAuthenticated } = await importApiServer();
    const response = await apiFetchAuthenticated("/api/v1/expenses");

    expect(response.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sessionMocks.clearSessionCookies).toHaveBeenCalled();
  });
});
