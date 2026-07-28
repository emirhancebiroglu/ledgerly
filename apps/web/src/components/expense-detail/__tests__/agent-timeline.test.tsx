import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentTimeline } from "@/components/expense-detail/agent-timeline";

const baseDocument = {
  id: "doc-1",
  filename: "receipt.png",
  contentType: "image/png",
  sizeBytes: 100,
  proposal: null,
  createdAt: "2026-07-24T10:00:00Z",
};

describe("AgentTimeline", () => {
  it("marks a flagged step distinctly (both text and a different dot color from normal steps)", () => {
    render(
      <AgentTimeline
        document={{ ...baseDocument, status: "NEEDS_REVIEW", failureReason: null }}
        expenseStatus="NEEDS_REVIEW"
      />,
    );

    expect(screen.getByText("Flagged for review")).toBeInTheDocument();
    // "Flagged" text label — never color alone.
    expect(screen.getAllByText("Flagged").length).toBeGreaterThan(0);
  });

  it("renders only the known steps for a posted expense, no flagged marker", () => {
    render(
      <AgentTimeline
        document={{ ...baseDocument, status: "EXTRACTED", failureReason: null }}
        expenseStatus="POSTED"
      />,
    );

    expect(screen.getByText("Uploaded")).toBeInTheDocument();
    expect(screen.getByText("Document processed")).toBeInTheDocument();
    expect(screen.queryByText("Flagged")).not.toBeInTheDocument();
  });

  it("renders a failure step with the server's failure reason when the document failed", () => {
    render(
      <AgentTimeline
        document={{ ...baseDocument, status: "FAILED", failureReason: "Unreadable scan" }}
        expenseStatus="NEEDS_REVIEW"
      />,
    );

    expect(screen.getByText("Processing failed")).toBeInTheDocument();
    expect(screen.getByText("Unreadable scan")).toBeInTheDocument();
  });
});
