import { beforeEach, describe, expect, it, vi } from "vitest";

const redirect = vi.fn();
const getAccessToken = vi.fn();

vi.mock("next/navigation", () => ({ redirect: (path: string) => redirect(path) }));
vi.mock("@/lib/session", () => ({ getAccessToken: () => getAccessToken() }));

import Home from "@/app/page";

describe("Home", () => {
  beforeEach(() => {
    redirect.mockReset();
    getAccessToken.mockReset();
  });

  it("sends a signed-in visitor to the dashboard", async () => {
    getAccessToken.mockResolvedValue("valid-token");

    await Home();

    expect(redirect).toHaveBeenCalledExactlyOnceWith("/dashboard");
  });

  it("sends a signed-out visitor to login", async () => {
    getAccessToken.mockResolvedValue(undefined);

    await Home();

    expect(redirect).toHaveBeenCalledExactlyOnceWith("/login");
  });

  it("renders no content of its own on either path", async () => {
    getAccessToken.mockResolvedValue("valid-token");

    await expect(Home()).resolves.toBeUndefined();
  });
});
