import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { UploadSteps } from "@/components/upload/upload-steps";

describe("UploadSteps", () => {
  it("shows Uploading active and the rest pending before any status arrives", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[]}
        connection="connecting"
      />,
    );

    expect(screen.getByRole("status", { name: "In progress" })).toBeInTheDocument();
    expect(screen.getByText("Extracting document data")).toBeInTheDocument();
  });

  it("marks observed activity done without inventing later stages", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[{ id: 1, stage: "UPLOADED", detail: null, createdAt: "2026-01-01T00:00:00Z" }, { id: 2, stage: "EXTRACTING", detail: null, createdAt: "2026-01-01T00:00:01Z" }]}
        connection="open"
      />,
    );

    expect(screen.getByText("Extracting document data")).toBeInTheDocument();
  });

  it("marks every step done on a terminal success status", () => {
    const { container } = render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[{ id: 1, stage: "UPLOADED", detail: null, createdAt: "2026-01-01T00:00:00Z" }, { id: 2, stage: "EXTRACTING", detail: null, createdAt: "2026-01-01T00:00:01Z" }, { id: 3, stage: "CATEGORIZING", detail: null, createdAt: "2026-01-01T00:00:02Z" }, { id: 4, stage: "DRAFTING_LEDGER", detail: null, createdAt: "2026-01-01T00:00:03Z" }, { id: 5, stage: "POSTED", detail: null, createdAt: "2026-01-01T00:00:04Z" }]}
        connection="open"
      />,
    );

    expect(screen.getByText("Posted to ledger")).toBeInTheDocument();
    expect(container.querySelectorAll('[role="status"]')).toHaveLength(0);
  });

  it("shows a failed indicator instead of a spinner or checkmark when processing fails", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[{ id: 1, stage: "UPLOADED", detail: null, createdAt: "2026-01-01T00:00:00Z" }, { id: 2, stage: "FAILED", detail: "Unreadable scan", createdAt: "2026-01-01T00:00:01Z" }]}
        connection="open"
      />,
    );

    expect(screen.getByText("Failed")).toBeInTheDocument();
  });

  it("does not show a future-stage spinner after categorization has failed", () => {
    const { container } = render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[
          { id: 1, stage: "UPLOADED", detail: null, createdAt: "2026-01-01T00:00:00Z" },
          { id: 2, stage: "EXTRACTING", detail: null, createdAt: "2026-01-01T00:00:01Z" },
          { id: 3, stage: "CATEGORIZING", detail: null, createdAt: "2026-01-01T00:00:02Z" },
          {
            id: 4,
            stage: "CATEGORIZATION_FAILED",
            detail: "Categorization could not be completed",
            createdAt: "2026-01-01T00:00:03Z",
          },
        ]}
        connection="open"
      />,
    );

    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(container.querySelectorAll('[role="status"]')).toHaveLength(0);
  });

  it("shows a stalled-connection notice when the SSE stream disconnects", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        activity={[]}
        connection="stalled"
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(/Lost connection/);
  });
});
