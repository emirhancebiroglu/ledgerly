"use client";

import { useEffect, useState } from "react";
import { FileWarning, Loader2 } from "lucide-react";
import { Card } from "@/components/ui/card";

const PREVIEWABLE_TYPES = new Set(["application/pdf", "image/png", "image/jpeg"]);

interface DocumentViewerProps {
  documentId: string;
  contentType: string;
  filename: string;
}

type ViewerState =
  | { status: "loading" }
  | { status: "ready"; blobUrl: string }
  | { status: "error" };

export function DocumentViewer({ documentId, contentType, filename }: DocumentViewerProps) {
  const [state, setState] = useState<ViewerState>({ status: "loading" });
  const isPreviewable = PREVIEWABLE_TYPES.has(contentType);

  useEffect(() => {
    if (!isPreviewable) {
      return;
    }

    let cancelled = false;
    let objectUrl: string | undefined;

    // The api serves this as Content-Disposition: attachment by design (DocumentController's
    // own comment: a document is arbitrary user-uploaded content, forcing a download instead of
    // inline rendering is what stops a polyglot file's payload from executing in this origin).
    // Fetching the bytes and building a blob: URL ourselves renders the content without ever
    // navigating the browser to that response directly.
    fetch(`/api/documents/${documentId}/content`)
      .then((response) => {
        if (!response.ok) {
          throw new Error(`content fetch failed: ${response.status}`);
        }
        return response.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setState({ status: "ready", blobUrl: objectUrl });
      })
      .catch(() => {
        if (!cancelled) {
          setState({ status: "error" });
        }
      });

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [documentId, isPreviewable]);

  return (
    <Card className="sticky top-6 gap-2 p-4">
      <div className="mb-1 text-[12.5px] font-medium text-muted-foreground">Source document</div>
      <div className="flex h-[420px] items-center justify-center overflow-hidden rounded-lg border border-dashed border-border bg-muted/40">
        {!isPreviewable && (
          <div className="flex flex-col items-center gap-2 px-6 text-center">
            <FileWarning className="size-6 text-muted-foreground" aria-hidden />
            <p className="text-[12.5px] text-muted-foreground">
              Preview isn&apos;t available for this file type ({contentType}).
            </p>
            <a
              href={`/api/documents/${documentId}/content`}
              download={filename}
              className="text-[12.5px] font-semibold text-primary"
            >
              Download {filename}
            </a>
          </div>
        )}
        {isPreviewable && state.status === "loading" && (
          <Loader2 className="size-6 animate-spin text-muted-foreground" aria-label="Loading document" />
        )}
        {isPreviewable && state.status === "error" && (
          <div className="flex flex-col items-center gap-2 px-6 text-center">
            <FileWarning className="size-6 text-muted-foreground" aria-hidden />
            <p className="text-[12.5px] text-muted-foreground">Couldn&apos;t load the document.</p>
          </div>
        )}
        {isPreviewable && state.status === "ready" && contentType === "application/pdf" && (
          <iframe src={state.blobUrl} title={filename} className="size-full" />
        )}
        {isPreviewable && state.status === "ready" && contentType !== "application/pdf" && (
          // eslint-disable-next-line @next/next/no-img-element -- blob: URL, next/image can't optimize it
          <img
            src={state.blobUrl}
            alt={`Receipt or invoice: ${filename}`}
            className="max-h-full max-w-full object-contain"
          />
        )}
      </div>
    </Card>
  );
}
