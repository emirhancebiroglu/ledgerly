import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { proxy } from "@/proxy";
import { ACCESS_TOKEN_COOKIE } from "@/lib/session";

function makeRequest(path: string, cookieValue?: string): NextRequest {
  const request = new NextRequest(new URL(path, "http://localhost:3000"));
  if (cookieValue) {
    request.cookies.set(ACCESS_TOKEN_COOKIE, cookieValue);
  }
  return request;
}

/** Every product route behind the app shell. None may render without a session cookie. */
const PROTECTED_PATHS = [
  "/dashboard",
  "/expenses",
  "/expenses/3f4c1b8e-0000-4000-8000-000000000000",
  "/upload",
  "/review",
  "/budgets",
];

describe("proxy", () => {
  it("redirects an unauthenticated request for a protected path to /login with a next param", () => {
    const response = proxy(makeRequest("/dashboard"));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("next")).toBe("/dashboard");
  });

  it.each(PROTECTED_PATHS)("keeps %s unreachable without a session", (path) => {
    const response = proxy(makeRequest(path));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/login");
    // Each route carries its own return destination rather than a shared default.
    expect(location.searchParams.get("next")).toBe(path);
  });

  it("lets an authenticated request through to a protected path", () => {
    const response = proxy(makeRequest("/dashboard", "valid-token"));

    expect(response.status).toBe(200);
    expect(response.headers.get("location")).toBeNull();
  });

  it("redirects an authenticated user away from /login back to /dashboard", () => {
    const response = proxy(makeRequest("/login", "valid-token"));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/dashboard");
  });

  it("redirects an authenticated user away from /register back to /dashboard", () => {
    const response = proxy(makeRequest("/register", "valid-token"));

    expect(response.status).toBe(307);
    expect(new URL(response.headers.get("location")!).pathname).toBe("/dashboard");
  });

  it("lets an unauthenticated request reach /login itself", () => {
    const response = proxy(makeRequest("/login"));

    expect(response.status).toBe(200);
    expect(response.headers.get("location")).toBeNull();
  });

  it("lets an unauthenticated request reach /register itself", () => {
    const response = proxy(makeRequest("/register"));

    expect(response.status).toBe(200);
    expect(response.headers.get("location")).toBeNull();
  });

  // `/` was previously exempted from the guard entirely and served a service-health screen,
  // which made it both the product's front door and a route no session check applied to.
  it("sends an authenticated visitor at / to the dashboard", () => {
    const response = proxy(makeRequest("/", "valid-token"));

    expect(response.status).toBe(307);
    expect(new URL(response.headers.get("location")!).pathname).toBe("/dashboard");
  });

  it("sends an unauthenticated visitor at / to login without a self-referential next param", () => {
    const response = proxy(makeRequest("/"));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/login");
    // "/" is the entry point, not a destination worth returning to after signing in.
    expect(location.searchParams.get("next")).toBeNull();
  });

  it("never leaves / rendering its own content", () => {
    for (const cookie of [undefined, "valid-token"]) {
      expect(proxy(makeRequest("/", cookie)).status).toBe(307);
    }
  });

  it("preserves the query string of a protected path in the next param", () => {
    const response = proxy(makeRequest("/expenses?status=NEEDS_REVIEW"));

    const next = new URL(response.headers.get("location")!).searchParams.get("next");
    expect(next).toBe("/expenses?status=NEEDS_REVIEW");
  });

  it("does not treat a path merely prefixed with a public route as public", () => {
    // `/loginsomething` must not slip through on a `startsWith` style check.
    const response = proxy(makeRequest("/login-history"));

    expect(response.status).toBe(307);
    expect(new URL(response.headers.get("location")!).pathname).toBe("/login");
  });
});

describe("proxy matcher", () => {
  it("excludes API routes and build assets so the guard never intercepts them", async () => {
    const { config } = await import("@/proxy");
    const pattern = new RegExp(`^${(config.matcher as string[])[0]}$`);

    expect(pattern.test("/dashboard")).toBe(true);
    expect(pattern.test("/")).toBe(true);
    expect(pattern.test("/api/v1/expenses")).toBe(false);
    expect(pattern.test("/_next/static/chunk.js")).toBe(false);
    expect(pattern.test("/_next/image")).toBe(false);
    expect(pattern.test("/favicon.ico")).toBe(false);
  });
});
