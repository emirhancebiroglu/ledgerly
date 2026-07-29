import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useDocumentStatus } from "@/components/upload/use-document-status";

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listeners: Record<string, ((event: { data: string }) => void)[]> = {};
  closed = false;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    (this.listeners[type] ??= []).push(listener);
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners[type] ?? []) {
      listener({ data: JSON.stringify(data) });
    }
  }

  close() {
    this.closed = true;
  }
}

describe("useDocumentStatus", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    MockEventSource.instances = [];
  });

  it("does nothing when documentId is null", () => {
    vi.stubGlobal("EventSource", MockEventSource);
    renderHook(() => useDocumentStatus(null));

    expect(MockEventSource.instances).toHaveLength(0);
  });

  it("connects to the events endpoint for the given document id", () => {
    vi.stubGlobal("EventSource", MockEventSource);
    renderHook(() => useDocumentStatus("doc-1"));

    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0].url).toBe("/api/documents/doc-1/events");
  });

  it("replays activity and closes the connection on a terminal outcome", async () => {
    vi.stubGlobal("EventSource", MockEventSource);
    const { result } = renderHook(() => useDocumentStatus("doc-1"));
    const source = MockEventSource.instances[0];

    act(() => {
      source.emit("activity", { id: 5, stage: "POSTED", detail: "Expense posted", createdAt: "2026-01-01T00:00:00Z" });
    });

    await waitFor(() => expect(result.current.activity[0]?.stage).toBe("POSTED"));
    expect(source.closed).toBe(true);
  });

  it("closes the connection when extraction needs review", async () => {
    vi.stubGlobal("EventSource", MockEventSource);
    const { result } = renderHook(() => useDocumentStatus("doc-1"));
    const source = MockEventSource.instances[0];

    act(() => {
      source.emit("activity", {
        id: 5,
        stage: "EXTRACTION_NEEDS_REVIEW",
        detail: "Tax total is inconsistent",
        createdAt: "2026-01-01T00:00:00Z",
      });
    });

    await waitFor(() => expect(result.current.activity[0]?.stage).toBe("EXTRACTION_NEEDS_REVIEW"));
    expect(source.closed).toBe(true);
  });

  it("marks the connection stalled and schedules a reconnect on a non-terminal disconnect", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("EventSource", MockEventSource);
    const { result } = renderHook(() => useDocumentStatus("doc-1"));
    const firstSource = MockEventSource.instances[0];

    act(() => {
      firstSource.onerror?.();
    });

    expect(result.current.connection).toBe("stalled");

    act(() => {
      vi.advanceTimersByTime(3000);
    });

    expect(MockEventSource.instances).toHaveLength(2);
  });

  it("does not reconnect after a disconnect that follows a terminal status", () => {
    vi.useFakeTimers();
    vi.stubGlobal("EventSource", MockEventSource);
    renderHook(() => useDocumentStatus("doc-1"));
    const source = MockEventSource.instances[0];

    act(() => {
      source.emit("activity", { id: 2, stage: "FAILED", detail: "bad scan", createdAt: "2026-01-01T00:00:00Z" });
    });
    act(() => {
      source.onerror?.();
    });
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    // Only the original connection — the terminal-status close is not treated as a stall.
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it("closes the connection and clears any pending reconnect timer on unmount", () => {
    vi.useFakeTimers();
    vi.stubGlobal("EventSource", MockEventSource);
    const { unmount } = renderHook(() => useDocumentStatus("doc-1"));
    const source = MockEventSource.instances[0];

    act(() => {
      source.onerror?.();
    });
    unmount();

    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(source.closed).toBe(true);
    // No second connection was ever opened after unmount.
    expect(MockEventSource.instances).toHaveLength(1);
  });
});
