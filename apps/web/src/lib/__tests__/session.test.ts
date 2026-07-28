import { afterEach, describe, expect, it, vi } from "vitest";

const cookieStore = vi.hoisted(() => ({
  set: vi.fn(),
  delete: vi.fn(),
  get: vi.fn(),
}));
const cookiesMock = vi.hoisted(() => vi.fn(async () => cookieStore));
vi.mock("next/headers", () => ({ cookies: cookiesMock }));

describe("session cookies", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
  });

  it("sets both tokens as httpOnly, path-scoped cookies with the API's own TTLs", async () => {
    const { setSessionCookies } = await import("@/lib/session");

    await setSessionCookies({ accessToken: "access-1", refreshToken: "refresh-1" });

    expect(cookieStore.set).toHaveBeenCalledWith(
      "ledgerly_access_token",
      "access-1",
      expect.objectContaining({ httpOnly: true, sameSite: "lax", path: "/", maxAge: 15 * 60 }),
    );
    expect(cookieStore.set).toHaveBeenCalledWith(
      "ledgerly_refresh_token",
      "refresh-1",
      expect.objectContaining({
        httpOnly: true,
        sameSite: "lax",
        path: "/",
        maxAge: 30 * 24 * 60 * 60,
      }),
    );
  });

  it("marks the cookie secure in production and not secure in dev/test", async () => {
    const originalEnv = process.env.NODE_ENV;

    vi.stubEnv("NODE_ENV", "production");
    vi.resetModules();
    const prod = await import("@/lib/session");
    await prod.setSessionCookies({ accessToken: "a", refreshToken: "r" });
    expect(cookieStore.set).toHaveBeenCalledWith(
      "ledgerly_access_token",
      "a",
      expect.objectContaining({ secure: true }),
    );

    cookieStore.set.mockClear();
    vi.stubEnv("NODE_ENV", "development");
    vi.resetModules();
    const dev = await import("@/lib/session");
    await dev.setSessionCookies({ accessToken: "a", refreshToken: "r" });
    expect(cookieStore.set).toHaveBeenCalledWith(
      "ledgerly_access_token",
      "a",
      expect.objectContaining({ secure: false }),
    );

    vi.stubEnv("NODE_ENV", originalEnv ?? "test");
  });

  it("deletes both cookies on clear", async () => {
    const { clearSessionCookies } = await import("@/lib/session");

    await clearSessionCookies();

    expect(cookieStore.delete).toHaveBeenCalledWith("ledgerly_access_token");
    expect(cookieStore.delete).toHaveBeenCalledWith("ledgerly_refresh_token");
  });
});
