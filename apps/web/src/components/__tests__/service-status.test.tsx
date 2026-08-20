import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServiceStatus } from "@/components/service-status";

describe("ServiceStatus", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shows healthy once the service responds ok", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true } as Response),
    );

    render(<ServiceStatus label="Api" url="http://api.test/health" />);

    await waitFor(() =>
      expect(screen.getByText("Healthy")).toBeInTheDocument(),
    );
  });

  it("shows unreachable when the service fetch rejects, without throwing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );

    render(<ServiceStatus label="Ai" url="http://ai.test/health" />);

    await waitFor(() =>
      expect(screen.getByText("Unreachable")).toBeInTheDocument(),
    );
  });

  it("shows unreachable when the service responds with a non-ok status", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false } as Response),
    );

    render(<ServiceStatus label="Ai" url="http://ai.test/health" />);

    await waitFor(() =>
      expect(screen.getByText("Unreachable")).toBeInTheDocument(),
    );
  });
});
