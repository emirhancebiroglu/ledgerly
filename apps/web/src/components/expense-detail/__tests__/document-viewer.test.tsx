import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DocumentViewer } from "@/components/expense-detail/document-viewer";

describe("DocumentViewer", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shows a fallback with a download link instead of attempting a preview for an unsupported type", () => {
    render(
      <DocumentViewer documentId="doc-4" contentType="text/plain" filename="notes.txt" />,
    );

    expect(screen.getByText(/Preview isn't available/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Download notes.txt/ })).toHaveAttribute(
      "href",
      "/api/documents/doc-4/content",
    );
  });

  it("renders an image for an image/* document once the blob loads", async () => {
    const blob = new Blob(["fake-png-bytes"], { type: "image/png" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(blob, { status: 200 })));
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:mock-url"),
      revokeObjectURL: vi.fn(),
    });

    render(<DocumentViewer documentId="doc-2" contentType="image/png" filename="receipt.png" />);

    await waitFor(() => {
      expect(screen.getByRole("img", { name: /receipt.png/ })).toBeInTheDocument();
    });
  });

  it("renders a PDF frame once the blob loads", async () => {
    const blob = new Blob(["fake-pdf-bytes"], { type: "application/pdf" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(blob, { status: 200 })));
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:mock-url"),
      revokeObjectURL: vi.fn(),
    });

    const { container } = render(
      <DocumentViewer documentId="doc-1" contentType="application/pdf" filename="invoice.pdf" />,
    );

    await waitFor(() => {
      expect(container.querySelector("iframe")).toBeInTheDocument();
    });
  });

  it("shows an error state instead of crashing when the blob fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));

    render(<DocumentViewer documentId="doc-1" contentType="application/pdf" filename="invoice.pdf" />);

    await waitFor(() => {
      expect(screen.getByText(/Couldn't load the document/)).toBeInTheDocument();
    });
  });
});
