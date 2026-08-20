import { afterEach, describe, expect, it, vi } from "vitest";
import { checkHealth } from "@/lib/health";

describe("checkHealth", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("resolves up when the response is ok", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true } as Response),
    );

    await expect(checkHealth("http://svc.test/health")).resolves.toBe("up");
  });

  it("resolves down when the response is not ok", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false } as Response),
    );

    await expect(checkHealth("http://svc.test/health")).resolves.toBe("down");
  });

  it("resolves down instead of hanging when the fetch never settles", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        (_url: string, init?: RequestInit) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () =>
              reject(new DOMException("Aborted", "AbortError")),
            );
          }),
      ),
    );

    const result = checkHealth("http://svc.test/health");
    await vi.advanceTimersByTimeAsync(3000);

    await expect(result).resolves.toBe("down");
  });
});
