import { afterEach, describe, expect, it, vi } from "vitest";

const redirectMock = vi.hoisted(() => vi.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
}));
vi.mock("next/navigation", () => ({ redirect: redirectMock }));

const sessionMocks = vi.hoisted(() => ({
  setSessionCookies: vi.fn(),
  clearSessionCookies: vi.fn(),
}));
vi.mock("@/lib/session", () => sessionMocks);

function formData(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) {
    data.set(key, value);
  }
  return data;
}

describe("login action", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.resetModules();
    redirectMock.mockClear();
    sessionMocks.setSessionCookies.mockClear();
    sessionMocks.clearSessionCookies.mockClear();
  });

  it("sets session cookies and redirects to /dashboard on valid credentials", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ accessToken: "a", refreshToken: "r" }), { status: 200 }),
      ),
    );

    const { login } = await import("@/app/actions/auth");

    await expect(
      login(undefined, formData({ email: "user@example.com", password: "pw" })),
    ).rejects.toThrow("NEXT_REDIRECT:/dashboard");

    expect(sessionMocks.setSessionCookies).toHaveBeenCalledWith({
      accessToken: "a",
      refreshToken: "r",
    });
  });

  it("redirects to the safe next path when provided", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ accessToken: "a", refreshToken: "r" }), { status: 200 }),
      ),
    );

    const { login } = await import("@/app/actions/auth");

    await expect(
      login(
        undefined,
        formData({ email: "user@example.com", password: "pw", next: "/expenses/123" }),
      ),
    ).rejects.toThrow("NEXT_REDIRECT:/expenses/123");
  });

  it("ignores a protocol-relative next path (open-redirect guard)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ accessToken: "a", refreshToken: "r" }), { status: 200 }),
      ),
    );

    const { login } = await import("@/app/actions/auth");

    await expect(
      login(undefined, formData({ email: "user@example.com", password: "pw", next: "//evil.com" })),
    ).rejects.toThrow("NEXT_REDIRECT:/dashboard");
  });

  it("returns the server's error message on a bad password instead of redirecting", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify({}), { status: 401 })),
    );

    const { login } = await import("@/app/actions/auth");

    const result = await login(
      undefined,
      formData({ email: "user@example.com", password: "wrong" }),
    );

    expect(result.error).toBe("Incorrect email or password.");
    expect(redirectMock).not.toHaveBeenCalled();
    expect(sessionMocks.setSessionCookies).not.toHaveBeenCalled();
  });
});

describe("logout action", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
  });

  it("clears the session cookies and redirects to /login", async () => {
    const { logout } = await import("@/app/actions/auth");

    await expect(logout()).rejects.toThrow("NEXT_REDIRECT:/login");
    expect(sessionMocks.clearSessionCookies).toHaveBeenCalled();
  });
});
