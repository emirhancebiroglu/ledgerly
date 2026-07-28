import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ExtractedFields } from "@/components/expense-detail/extracted-fields";

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
  it("renders vendor, amount, and confidence as a percentage", () => {
    render(<ExtractedFields expense={baseExpense} />);

    expect(screen.getByText("$123.45")).toBeInTheDocument();
    expect(screen.getByText("87%")).toBeInTheDocument();
  });

  it("falls back to a placeholder instead of a blank field when vendor is null", () => {
    render(<ExtractedFields expense={{ ...baseExpense, vendor: null }} />);

    expect(screen.getByText("Unknown vendor")).toBeInTheDocument();
  });
});
