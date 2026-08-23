"use client";

import { useRef, useState, type DragEvent } from "react";
import { CircleCheck, CircleAlert, FileWarning, Upload, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { uploadPolicyDocument, type PolicyDocument } from "@/lib/policies";

type UploadState =
  | { kind: "idle" }
  | { kind: "inflight"; filename: string }
  | { kind: "success"; filename: string; chunkCount: number; document: PolicyDocument }
  | { kind: "failed"; filename: string; message: string }
  | { kind: "rejected"; filename: string };

interface PolicyUploadPanelProps {
  onClose: () => void;
  onUploaded: (document: PolicyDocument) => void;
}

/**
 * Upload is synchronous — `POST /api/v1/policies` does not return until the outcome is final —
 * so this models a genuine multi-second in-flight request, never an optimistic row. See
 * `docs/design/m9.7/README.md`'s "Upload states" section.
 */
export function PolicyUploadPanel({ onClose, onUploaded }: PolicyUploadPanelProps) {
  const [state, setState] = useState<UploadState>({ kind: "idle" });
  const [isDragOver, setIsDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  async function submit(file: File) {
    if (file.type !== "application/pdf") {
      setState({ kind: "rejected", filename: file.name });
      return;
    }
    setState({ kind: "inflight", filename: file.name });
    const result = await uploadPolicyDocument(file, crypto.randomUUID());
    if (!result.ok) {
      setState({ kind: "failed", filename: file.name, message: result.message });
      return;
    }
    setState({
      kind: "success",
      filename: file.name,
      chunkCount: result.document.chunkCount,
      document: result.document,
    });
    onUploaded(result.document);
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragOver(false);
    const file = event.dataTransfer.files[0];
    if (file && state.kind !== "inflight") {
      void submit(file);
    }
  }

  return (
    <div className="rounded-xl border border-border bg-card p-[18px] shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <div className="text-[11.5px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
          Upload a policy PDF
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close upload panel"
          className="p-0.5 text-muted-foreground"
        >
          <X className="size-[15px]" aria-hidden />
        </button>
      </div>

      {state.kind === "idle" && (
        <div
          role="button"
          tabIndex={0}
          onClick={() => inputRef.current?.click()}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              inputRef.current?.click();
            }
          }}
          onDragOver={(event) => {
            event.preventDefault();
            setIsDragOver(true);
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
          className={cn(
            "mt-3.5 cursor-pointer rounded-xl border-[1.5px] border-dashed border-border bg-background/50 px-5 py-[34px] text-center",
            isDragOver && "border-primary bg-accent-soft",
          )}
        >
          <input
            ref={inputRef}
            type="file"
            accept="application/pdf"
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void submit(file);
              event.target.value = "";
            }}
          />
          <Upload className="mx-auto mb-[11px] size-[26px] text-accent-foreground" strokeWidth={1.7} aria-hidden />
          <div className="text-[13.5px] font-semibold">Drop a policy PDF here, or browse</div>
          <div className="mt-[5px] text-[12.5px] leading-[1.5] text-muted-foreground">
            PDF only. Embedding runs during the upload and takes a few seconds — keep this tab
            open until it finishes.
          </div>
        </div>
      )}

      {state.kind === "inflight" && (
        <div className="mt-3.5 rounded-xl border border-border bg-background/50 px-5 py-[22px]">
          <div className="flex items-center gap-[11px]">
            <span
              className="size-[18px] shrink-0 animate-spin rounded-full border-2 border-muted border-t-primary"
              aria-hidden
            />
            <div className="min-w-0 truncate font-mono text-[12.5px] font-medium">{state.filename}</div>
          </div>
          <div className="mt-2.5 text-[12.5px] leading-[1.55] text-muted-foreground">
            Splitting the document into passages and embedding each one. This request stays open
            until the outcome is final.
          </div>
          <div className="mt-3.5 h-1 overflow-hidden rounded-full bg-muted" role="progressbar" aria-label="Embedding in progress">
            <div className="h-full w-1/3 animate-pulse rounded-full bg-primary" />
          </div>
        </div>
      )}

      {state.kind === "success" && (
        <div className="mt-3.5 rounded-xl border border-success/30 bg-success-soft px-5 py-5">
          <div className="flex items-center gap-2.5">
            <CircleCheck className="size-[18px] shrink-0 text-success-foreground" aria-hidden />
            <div className="min-w-0 truncate font-mono text-[12.5px] font-medium">{state.filename}</div>
          </div>
          <div className="mt-2 text-[12.5px] text-success-foreground">
            Indexed. Split into{" "}
            <span className="font-mono font-semibold tabular-nums">{state.chunkCount}</span>{" "}
            passages, searchable now.
          </div>
          <div className="mt-3.5 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg bg-primary px-3.5 py-1.5 text-[12.5px] font-semibold text-primary-foreground"
            >
              Done
            </button>
            <button
              type="button"
              onClick={() => setState({ kind: "idle" })}
              className="rounded-lg border border-border bg-card px-3.5 py-1.5 text-[12.5px] font-medium"
            >
              Upload another
            </button>
          </div>
        </div>
      )}

      {state.kind === "failed" && (
        <div className="mt-3.5 rounded-xl border border-danger/30 bg-danger-soft px-5 py-5">
          <div className="flex items-center gap-2.5">
            <CircleAlert className="size-[18px] shrink-0 text-danger-foreground" aria-hidden />
            <div className="min-w-0 truncate font-mono text-[12.5px] font-medium">{state.filename}</div>
          </div>
          <div className="mt-2 text-[12.5px] leading-[1.55] text-danger-foreground">
            {state.message}
          </div>
          <button
            type="button"
            onClick={() => setState({ kind: "idle" })}
            className="mt-3.5 rounded-lg border border-danger/30 bg-card px-3.5 py-1.5 text-[12.5px] font-semibold text-danger-foreground"
          >
            Re-upload
          </button>
        </div>
      )}

      {state.kind === "rejected" && (
        <div className="mt-3.5 rounded-xl border-[1.5px] border-dashed border-danger/40 bg-danger-soft/60 px-5 py-5">
          <div className="flex items-center gap-2.5">
            <FileWarning className="size-[18px] shrink-0 text-danger-foreground" aria-hidden />
            <div className="min-w-0 truncate font-mono text-[12.5px] font-medium">{state.filename}</div>
          </div>
          <div className="mt-2 text-[12.5px] leading-[1.55] text-danger-foreground">
            Not a PDF. Only PDF documents can be split and embedded, so this file was not
            uploaded.
          </div>
          <button
            type="button"
            onClick={() => setState({ kind: "idle" })}
            className="mt-3.5 rounded-lg border border-border bg-card px-3.5 py-1.5 text-[12.5px] font-semibold"
          >
            Choose another file
          </button>
        </div>
      )}
    </div>
  );
}
