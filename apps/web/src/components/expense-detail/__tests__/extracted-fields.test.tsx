import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ExtractedFields } from "@/components/expense-detail/extracted-fields";
import type { ExpenseDetail } from "@/lib/expense-detail";

const baseExpense = {
  id: "1",
  documentId: "d1",
  vendor: "Acme Corp",
  categoryId: "cat-1",
  ledgerTransactionId: "txn-1",
  amountMinor: 12345,
  currency: "USD",
  categorizationConfidence: 0.87,
  citation: null,
  status: "POSTED" as const,
  createdAt: "2026-07-01T00:00:00Z",
  ledgerEntries: [],
  invoiceNumber: "INV-42",
  documentDate: "2026-07-01",
  taxMinor: "1200",
  activity: [],
  document: {
    id: "d1",
    filename: "receipt.png",
    contentType: "image/png",
    sizeBytes: 100,
    status: "EXTRACTED" as const,
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-01T00:00:00Z",
  },
};

describe("ExtractedFields", () => {
  it("renders the validated proposal fields, amount, tax and confidence", () => {
    render(<ExtractedFields expense={baseExpense} />);

    expect(screen.getByText("$123.45")).toBeInTheDocument();
    expect(screen.getByText("$12.00")).toBeInTheDocument();
    expect(screen.getByText("INV-42")).toBeInTheDocument();
    expect(screen.getByText("87%")).toBeInTheDocument();
  });

  it("falls back to a placeholder instead of a blank field when vendor is null", () => {
    render(<ExtractedFields expense={{ ...baseExpense, vendor: null }} />);

    expect(screen.getByText("Unknown vendor")).toBeInTheDocument();
  });

  it("renders zero tax and reserves the placeholder for a truly unavailable value", () => {
    const { rerender } = render(<ExtractedFields expense={{ ...baseExpense, taxMinor: "0" }} />);

    expect(screen.getByText("$0.00")).toBeInTheDocument();

    rerender(<ExtractedFields expense={{ ...baseExpense, taxMinor: null }} />);

    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("handles a legacy response that omits taxMinor entirely", () => {
    render(
      <ExtractedFields
        expense={{ ...baseExpense, taxMinor: undefined } as unknown as ExpenseDetail}
      />,
    );

    expect(screen.getByText("—")).toBeInTheDocument();
  });
});
