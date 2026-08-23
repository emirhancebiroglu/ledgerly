import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PolicyDetail } from "@/components/policies/policy-detail";
import type { PolicyChunk, PolicyDocument } from "@/lib/policies";

function doc(overrides: Partial<PolicyDocument>): PolicyDocument {
  return {
    id: "id-1",
    filename: "policy.pdf",
    status: "EMBEDDED",
    failureReason: null,
    createdAt: "2026-08-12T00:00:00Z",
    chunkCount: 0,
    ...overrides,
  };
}

const CHUNKS: PolicyChunk[] = [
  { index: 0, text: "Meals are reimbursed up to 50 EUR per day." },
  { index: 1, text: "Mileage is reimbursed at the statutory rate." },
  { index: 2, text: "Receipts are required for any expense of 25 EUR or more." },
];

describe("PolicyDetail", () => {
  it("renders passages in ascending index order with the exact stored text", () => {
    render(<PolicyDetail document={doc({ status: "EMBEDDED", chunkCount: 3 })} chunks={CHUNKS} />);

    const texts = screen.getAllByText(/reimbursed|Receipts/);
    expect(texts[0]).toHaveTextContent("Meals are reimbursed up to 50 EUR per day.");
    expect(texts[1]).toHaveTextContent("Mileage is reimbursed at the statutory rate.");
  });

  it("filters passages by search and shows the empty-search copy when nothing matches", () => {
    render(<PolicyDetail document={doc({ status: "EMBEDDED", chunkCount: 3 })} chunks={CHUNKS} />);

    fireEvent.change(screen.getByPlaceholderText(/Search this document/), {
      target: { value: "mileage" },
    });

    expect(screen.getByText(/Mileage is reimbursed/)).toBeInTheDocument();
    expect(screen.queryByText(/Meals are reimbursed/)).not.toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText(/Search this document/), {
      target: { value: "zzz-no-match" },
    });

    expect(screen.getByText(/No passage matches "zzz-no-match"/)).toBeInTheDocument();
  });

  it("reveals remaining passages via the fold with the true remaining count", () => {
    const many: PolicyChunk[] = Array.from({ length: 9 }, (_, i) => ({ index: i, text: `Passage ${i}.` }));
    render(<PolicyDetail document={doc({ status: "EMBEDDED", chunkCount: 9 })} chunks={many} />);

    expect(screen.queryByText("Passage 7.")).not.toBeInTheDocument();
    expect(screen.getByText("Show 3 more")).toBeInTheDocument();

    fireEvent.click(screen.getByText("Show 3 more"));

    expect(screen.getByText("Passage 7.")).toBeInTheDocument();
  });

  it("renders the failure card and never an empty passage list for a FAILED document", () => {
    render(
      <PolicyDetail
        document={doc({ status: "FAILED", failureReason: "pdf_text_extraction_empty: scanned image" })}
        chunks={[]}
      />,
    );

    expect(screen.getByText(/Embedding failed/)).toBeInTheDocument();
    expect(screen.getByText(/not partially indexed/)).toBeInTheDocument();
    expect(screen.getByText(/pdf_text_extraction_empty: scanned image/)).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/Search this document/)).not.toBeInTheDocument();
  });

  it("renders the in-progress card for a PROCESSING document", () => {
    render(<PolicyDetail document={doc({ status: "PROCESSING" })} chunks={[]} />);

    expect(screen.getByText("Splitting and embedding")).toBeInTheDocument();
  });

  it("renders the queued card for a PENDING document", () => {
    render(<PolicyDetail document={doc({ status: "PENDING" })} chunks={[]} />);

    expect(screen.getByText("Queued for embedding")).toBeInTheDocument();
  });
});
