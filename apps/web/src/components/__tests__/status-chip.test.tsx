import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusChip } from "@/components/status-chip";

describe("StatusChip", () => {
  it("renders posted and needs-review with different text and a distinct icon each", () => {
    const { container: postedContainer } = render(<StatusChip status="POSTED" />);
    expect(screen.getByText("Posted")).toBeInTheDocument();
    expect(postedContainer.querySelector("svg")).toBeInTheDocument();

    const { container: reviewContainer } = render(<StatusChip status="NEEDS_REVIEW" />);
    expect(screen.getByText("Needs review")).toBeInTheDocument();
    expect(reviewContainer.querySelector("svg")).toBeInTheDocument();
  });
});
