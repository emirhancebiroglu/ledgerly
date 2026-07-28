import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ExpensesFilters } from "@/components/expenses/expenses-filters";

const pushMock = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
  usePathname: () => "/expenses",
  useSearchParams: () => new URLSearchParams(""),
}));

describe("ExpensesFilters search debounce", () => {
  afterEach(() => {
    vi.useRealTimers();
    pushMock.mockClear();
  });

  it("does not push a stale search after the input unmounts before the debounce fires", () => {
    vi.useFakeTimers();

    const { unmount } = render(<ExpensesFilters />);
    fireEvent.change(screen.getByLabelText("Search expenses"), { target: { value: "fig" } });

    // Unmount before the 300ms debounce elapses (e.g. navigating away via a sidebar click).
    unmount();
    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(pushMock).not.toHaveBeenCalled();
  });

  it("still pushes normally when the component stays mounted past the debounce", () => {
    vi.useFakeTimers();

    render(<ExpensesFilters />);
    fireEvent.change(screen.getByLabelText("Search expenses"), { target: { value: "fig" } });
    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(pushMock).toHaveBeenCalledWith("/expenses?search=fig");
  });
});
