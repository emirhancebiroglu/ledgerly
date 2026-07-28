import { render, screen, waitFor } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReviewQueueTable } from "@/components/review/review-queue-table";

const actionMocks = vi.hoisted(() => ({
  approveExpense: vi.fn(),
  correctExpense: vi.fn(),
}));
vi.mock("@/lib/expense-actions", () => actionMocks);

const categories = [
  { id: "cat-1", name: "Software" },
  { id: "cat-2", name: "Travel" },
];

function expense(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "exp-1",
    documentId: "doc-1",
    vendor: "Office Depot",
    categoryId: "cat-1",
    ledgerTransactionId: null,
    amountMinor: 15600,
    currency: "EUR",
    categorizationConfidence: 0.62,
    citation: null,
    status: "NEEDS_REVIEW" as const,
    createdAt: "2026-07-22T14:00:00Z",
    ...overrides,
  };
}

describe("ReviewQueueTable", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    actionMocks.approveExpense.mockClear();
    actionMocks.correctExpense.mockClear();
  });

  it("renders an empty state instead of a blank card when nothing needs review", () => {
    render(<ReviewQueueTable initialExpenses={[]} categories={categories} />);

    expect(screen.getByText(/Nothing needs review/)).toBeInTheDocument();
  });

  it("Approve selected is hidden until a row is checked, then appears", () => {
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    expect(screen.queryByRole("button", { name: "Approve selected" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: /Select Office Depot/ }));

    expect(screen.getByRole("button", { name: "Approve selected" })).toBeInTheDocument();
  });

  it("approving a row removes exactly that row, none other", async () => {
    actionMocks.approveExpense.mockResolvedValue({ ok: true });
    render(
      <ReviewQueueTable
        initialExpenses={[expense({ id: "exp-1", vendor: "Office Depot" }), expense({ id: "exp-2", vendor: "Rideshare Co." })]}
        categories={categories}
      />,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Approve" })[0]);

    await waitFor(() => {
      expect(screen.queryByText("Office Depot")).not.toBeInTheDocument();
    });
    expect(screen.getByText("Rideshare Co.")).toBeInTheDocument();
    expect(actionMocks.approveExpense).toHaveBeenCalledWith("exp-1");
  });

  it("a failed approve rolls the optimistic removal back and shows the error", async () => {
    actionMocks.approveExpense.mockResolvedValue({
      ok: false,
      status: 500,
      message: "Something went wrong.",
    });
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    // Optimistically removed first...
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Something went wrong.");
    });
    // ...then rolled back — the row is back in the list alongside its error.
    expect(screen.getByText("Office Depot")).toBeInTheDocument();
  });

  it("a 409 (already resolved elsewhere) is reported, not silently swallowed", async () => {
    actionMocks.approveExpense.mockResolvedValue({
      ok: false,
      status: 409,
      message: "This expense has already been resolved",
    });
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(/already been resolved/);
    });
  });

  it("approve selected only resolves the checked rows, not every row", async () => {
    actionMocks.approveExpense.mockResolvedValue({ ok: true });
    render(
      <ReviewQueueTable
        initialExpenses={[
          expense({ id: "exp-1", vendor: "Office Depot" }),
          expense({ id: "exp-2", vendor: "Rideshare Co." }),
        ]}
        categories={categories}
      />,
    );

    fireEvent.click(screen.getByRole("checkbox", { name: /Select Office Depot/ }));
    fireEvent.click(screen.getByRole("button", { name: "Approve selected" }));

    await waitFor(() => {
      expect(actionMocks.approveExpense).toHaveBeenCalledTimes(1);
    });
    expect(actionMocks.approveExpense).toHaveBeenCalledWith("exp-1");
  });

  it("bulk approving three rows keeps the failed one in place, not resorted or dropped", async () => {
    actionMocks.approveExpense.mockImplementation((id: string) =>
      Promise.resolve(
        id === "exp-2"
          ? { ok: false, status: 500, message: "Something went wrong." }
          : { ok: true },
      ),
    );
    render(
      <ReviewQueueTable
        initialExpenses={[
          expense({ id: "exp-1", vendor: "First" }),
          expense({ id: "exp-2", vendor: "Second" }),
          expense({ id: "exp-3", vendor: "Third" }),
        ]}
        categories={categories}
      />,
    );

    fireEvent.click(screen.getByRole("checkbox", { name: /Select First/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /Select Second/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /Select Third/ }));
    fireEvent.click(screen.getByRole("button", { name: "Approve selected" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Something went wrong.");
    });

    expect(screen.queryByText("First", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText("Third", { exact: true })).not.toBeInTheDocument();
    expect(screen.getByText("Second", { exact: true })).toBeInTheDocument();
    expect(actionMocks.approveExpense).toHaveBeenCalledTimes(3);
  });

  it("clicking Approve twice before the first request resolves only fires one call", async () => {
    let resolveFirst: (value: { ok: true }) => void = () => {};
    actionMocks.approveExpense.mockReturnValue(
      new Promise((resolve) => {
        resolveFirst = resolve;
      }),
    );
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    const button = screen.getByRole("button", { name: "Approve" });
    fireEvent.click(button);
    fireEvent.click(button);

    expect(actionMocks.approveExpense).toHaveBeenCalledTimes(1);
    resolveFirst({ ok: true });
  });

  it("correcting a row calls correctExpense with the chosen category", async () => {
    actionMocks.correctExpense.mockResolvedValue({ ok: true });
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    fireEvent.change(screen.getByLabelText(/Correct category for Office Depot/), {
      target: { value: "cat-2" },
    });

    await waitFor(() => {
      expect(actionMocks.correctExpense).toHaveBeenCalledWith("exp-1", "cat-2");
    });
  });

  it("checkboxes are real inputs operable via keyboard/change events", () => {
    render(<ReviewQueueTable initialExpenses={[expense()]} categories={categories} />);

    const checkbox = screen.getByRole("checkbox", { name: /Select Office Depot/ });
    expect(checkbox).toHaveAttribute("type", "checkbox");
    expect(checkbox).not.toBeDisabled();
  });
});
