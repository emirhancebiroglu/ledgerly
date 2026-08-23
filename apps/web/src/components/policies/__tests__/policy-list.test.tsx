import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PolicyList } from "@/components/policies/policy-list";
import type { PolicyDocument } from "@/lib/policies";

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

describe("PolicyList", () => {
  it("renders the load-error state and nothing else when the list failed to load", () => {
    render(<PolicyList initialDocuments={[]} loadError />);

    expect(screen.getByText(/Couldn't load policy documents/)).toBeInTheDocument();
    expect(screen.queryByText("Documents indexed")).not.toBeInTheDocument();
  });

  it("renders the empty-organization state when there are no documents", () => {
    render(<PolicyList initialDocuments={[]} loadError={false} />);

    expect(screen.getByText("No policy documents yet")).toBeInTheDocument();
  });

  it("computes every stat from the listed documents rather than hardcoding them", () => {
    render(
      <PolicyList
        initialDocuments={[
          doc({ id: "1", status: "EMBEDDED", chunkCount: 12 }),
          doc({ id: "2", status: "EMBEDDED", chunkCount: 7 }),
          doc({ id: "3", status: "FAILED", failureReason: "boom" }),
        ]}
        loadError={false}
      />,
    );

    expect(screen.getByText("Documents indexed").closest("div")?.parentElement).toHaveTextContent("2");
    expect(screen.getByText("Passages indexed").closest("div")?.parentElement).toHaveTextContent("19");
    expect(screen.getByText("needs re-upload")).toBeInTheDocument();
  });

  it("shows the neutral 'nothing to fix' note rather than a red zero when there are no failures", () => {
    render(<PolicyList initialDocuments={[doc({ status: "EMBEDDED" })]} loadError={false} />);

    expect(screen.getByText("nothing to fix")).toBeInTheDocument();
  });

  it("renders a failed row's failureReason", () => {
    render(
      <PolicyList
        initialDocuments={[doc({ status: "FAILED", failureReason: "pdf_text_extraction_empty: scanned image" })]}
        loadError={false}
      />,
    );

    expect(screen.getByText(/pdf_text_extraction_empty: scanned image/)).toBeInTheDocument();
  });

  it("renders an em dash for chunk count on a document that is not EMBEDDED", () => {
    render(
      <PolicyList initialDocuments={[doc({ status: "PENDING", chunkCount: 0 })]} loadError={false} />,
    );

    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("filters to Processing including both PROCESSING and PENDING documents", () => {
    render(
      <PolicyList
        initialDocuments={[
          doc({ id: "1", filename: "processing.pdf", status: "PROCESSING" }),
          doc({ id: "2", filename: "queued.pdf", status: "PENDING" }),
          doc({ id: "3", filename: "indexed.pdf", status: "EMBEDDED" }),
        ]}
        loadError={false}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Processing 2/ }));

    expect(screen.getByText("processing.pdf")).toBeInTheDocument();
    expect(screen.getByText("queued.pdf")).toBeInTheDocument();
    expect(screen.queryByText("indexed.pdf")).not.toBeInTheDocument();
  });

  it("renders the filter-empty state when a filter yields nothing", () => {
    render(<PolicyList initialDocuments={[doc({ status: "EMBEDDED" })]} loadError={false} />);

    fireEvent.click(screen.getByRole("button", { name: /Failed 0/ }));

    expect(screen.getByText("No policies in this filter")).toBeInTheDocument();
  });
});
