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

  it("always offers an 'Open in new tab' escape hatch alongside the PDF frame", async () => {
    // Some mobile Chrome builds have no PDF plugin, so the iframe falls back to a bare "Open"
    // control that tries (and silently fails) to open the blob: URL in a new top-level browsing
    // context — blob: URLs only resolve within the context that created them. This link, opened
    // with target="_blank" from the same page, stays in a context where the URL is still valid.
    const blob = new Blob(["fake-pdf-bytes"], { type: "application/pdf" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(blob, { status: 200 })));
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:mock-url"),
      revokeObjectURL: vi.fn(),
    });

    render(
      <DocumentViewer documentId="doc-1" contentType="application/pdf" filename="invoice.pdf" />,
    );

    await waitFor(() => {
      const link = screen.getByRole("link", { name: "Open in new tab" });
      expect(link).toHaveAttribute("href", "blob:mock-url");
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener");
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
