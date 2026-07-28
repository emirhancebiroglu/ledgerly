"use client";

import { useState } from "react";
import Link from "next/link";
import { uploadDocument, type UploadedDocument } from "@/lib/document-upload";
import { useDocumentStatus } from "@/components/upload/use-document-status";
import { DropZone } from "@/components/upload/drop-zone";
import { UploadSteps } from "@/components/upload/upload-steps";

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${Math.round(bytes / 1024)} KB`;
}

type FlowState =
  | { phase: "idle" }
  | { phase: "uploading"; filename: string; sizeBytes: number }
  | { phase: "tracking"; document: UploadedDocument }
  | { phase: "rejected"; filename: string; message: string };

export function UploadFlow() {
  const [flow, setFlow] = useState<FlowState>({ phase: "idle" });
  const trackingId = flow.phase === "tracking" ? flow.document.id : null;
  const { status, failureReason, connection } = useDocumentStatus(trackingId);

  async function handleFile(file: File) {
    setFlow({ phase: "uploading", filename: file.name, sizeBytes: file.size });
    const idempotencyKey = crypto.randomUUID();
    const result = await uploadDocument(file, idempotencyKey);

    if (!result.ok) {
      setFlow({ phase: "rejected", filename: file.name, message: result.message });
      return;
    }
    setFlow({ phase: "tracking", document: result.document });
  }

  const isBusy = flow.phase === "uploading" || flow.phase === "tracking";
  const failed = status === "FAILED";
  const terminal = status === "EXTRACTED" || status === "NEEDS_REVIEW" || status === "FAILED";

  return (
    <div className="mx-auto flex max-w-[640px] flex-col gap-5">
      <DropZone onFileSelected={handleFile} disabled={isBusy} />

      {flow.phase === "rejected" && (
        <div
          role="alert"
          className="rounded-xl border border-danger/30 bg-danger-soft p-[14px_18px] text-[13px] text-danger"
        >
          <span className="font-semibold">{flow.filename}</span> couldn&apos;t be uploaded:{" "}
          {flow.message}
        </div>
      )}

      {flow.phase === "uploading" && (
        <UploadSteps
          filename={flow.filename}
          sizeLabel={formatSize(flow.sizeBytes)}
          documentStatus={null}
          failed={false}
          connection="connecting"
        />
      )}

      {flow.phase === "tracking" && (
        <>
          <UploadSteps
            filename={flow.document.filename}
            sizeLabel={formatSize(flow.document.sizeBytes)}
            documentStatus={status}
            failed={failed}
            connection={connection}
          />
          {failed && (
            <div
              role="alert"
              className="rounded-xl border border-danger/30 bg-danger-soft p-[14px_18px] text-[13px] text-danger"
            >
              Processing failed: {failureReason ?? "No further detail available."}
            </div>
          )}
          {terminal && !failed && (
            <Link
              href="/expenses"
              className="text-center text-[12.5px] font-semibold text-primary"
            >
              View in expenses →
            </Link>
          )}
        </>
      )}
    </div>
  );
}
