import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { UploadSteps } from "@/components/upload/upload-steps";

describe("UploadSteps", () => {
  it("shows Uploading active and the rest pending before any status arrives", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        documentStatus={null}
        failed={false}
        connection="connecting"
      />,
    );

    expect(screen.getByRole("status", { name: "In progress" })).toBeInTheDocument();
    expect(screen.getByText("Processing")).toBeInTheDocument();
  });

  it("marks Uploading done and Processing active once PENDING/PROCESSING arrives", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        documentStatus="PROCESSING"
        failed={false}
        connection="open"
      />,
    );

    // Exactly one spinner (Processing) once uploading is done.
    expect(screen.getAllByRole("status", { name: "In progress" })).toHaveLength(1);
  });

  it("marks every step done on a terminal success status", () => {
    const { container } = render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        documentStatus="EXTRACTED"
        failed={false}
        connection="open"
      />,
    );

    expect(screen.getByText("Complete")).toBeInTheDocument();
    expect(container.querySelectorAll('[role="status"]')).toHaveLength(0);
  });

  it("shows a failed indicator instead of a spinner or checkmark when processing fails", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        documentStatus="FAILED"
        failed={true}
        connection="open"
      />,
    );

    expect(screen.getByText("Failed")).toBeInTheDocument();
  });

  it("shows a stalled-connection notice when the SSE stream disconnects", () => {
    render(
      <UploadSteps
        filename="receipt.pdf"
        sizeLabel="247 KB"
        documentStatus="PROCESSING"
        failed={false}
        connection="stalled"
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(/Lost connection/);
  });
});
