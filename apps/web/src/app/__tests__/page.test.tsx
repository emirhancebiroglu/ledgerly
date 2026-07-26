import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Home from "@/app/page";

describe("Home", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders both service indicators independently when ai is unreachable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.includes("8000")) {
          return Promise.reject(new TypeError("Failed to fetch"));
        }
        return Promise.resolve({ ok: true } as Response);
      }),
    );

    render(<Home />);

    expect(screen.getByText("Api")).toBeInTheDocument();
    expect(screen.getByText("Ai")).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText("Healthy")).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText("Unreachable")).toBeInTheDocument());
  });
});
