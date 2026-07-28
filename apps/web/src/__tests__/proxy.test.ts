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

describe("proxy", () => {
  it("redirects an unauthenticated request for a protected path to /login with a next param", () => {
    const response = proxy(makeRequest("/dashboard"));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("next")).toBe("/dashboard");
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

  it("lets an unauthenticated request reach /login itself", () => {
    const response = proxy(makeRequest("/login"));

    expect(response.status).toBe(200);
    expect(response.headers.get("location")).toBeNull();
  });
});
