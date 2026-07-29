import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentTimeline } from "@/components/expense-detail/agent-timeline";

describe("AgentTimeline", () => {
  it("renders persisted stages in order and marks a review outcome by icon and text", () => {
    const { container } = render(
      <AgentTimeline
        activity={[
          { id: 1, stage: "UPLOADED", detail: "Document uploaded", createdAt: "2026-07-24T10:00:00Z" },
          { id: 2, stage: "CATEGORIZING", detail: "Categorizing expense", createdAt: "2026-07-24T10:01:00Z" },
          { id: 3, stage: "NEEDS_REVIEW", detail: "Expense needs review", createdAt: "2026-07-24T10:02:00Z" },
        ]}
      />,
    );

    expect(screen.getByText("Uploaded")).toBeInTheDocument();
    expect(screen.getAllByText("Categorizing expense").length).toBeGreaterThan(0);
    expect(screen.getByText("Needs review")).toBeInTheDocument();
    expect(screen.getByText("Flagged")).toBeInTheDocument();
    expect(container.querySelector("svg.lucide-triangle-alert")).toBeInTheDocument();
  });

  it("does not invent activity when a legacy expense has no history", () => {
    render(<AgentTimeline activity={[]} />);
    expect(screen.getByText("No agent activity has been recorded yet.")).toBeInTheDocument();
  });
});
