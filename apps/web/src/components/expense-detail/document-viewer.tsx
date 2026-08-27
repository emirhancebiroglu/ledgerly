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
    // sticky only from the shell breakpoint up, where the page is a real two-column grid
    // (apps/web/src/app/(app)/expenses/[id]/page.tsx's grid-cols-1 shell:grid-cols-2) — below it
    // the two panels stack into one column, and a sticky first panel pins itself to the top of
    // the viewport as the page scrolls, sliding over whatever's stacked beneath it instead of
    // scrolling past it.
    <Card className="gap-2 p-4 shell:sticky shell:top-6">
      <div className="mb-1 text-[12.5px] font-medium text-muted-foreground">Source document</div>
      <div className="relative flex h-[420px] items-center justify-center overflow-hidden rounded-lg border border-dashed border-border bg-muted/40">
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
        {/* Rendering the PDF via an iframe delegates to the browser's own PDF plugin, which
            some mobile Chrome builds don't have — there the iframe falls back to a bare "Open"
            control instead of a preview, and that control tries to open the blob: URL in a new
            top-level browsing context where it's invalid (blob: URLs only resolve within the
            context that created them), so the button visibly does nothing. This link opens the
            same blob: URL with `target="_blank"`, which browsers keep same-context (a new tab
            sharing the opener's origin/session), so it resolves where the plugin fallback
            didn't — a real way out on exactly the platforms where the embed silently failed. */}
        {isPreviewable && state.status === "ready" && contentType === "application/pdf" && (
          <a
            href={state.blobUrl}
            target="_blank"
            rel="noopener"
            className="absolute right-2 bottom-2 rounded-md bg-background/90 px-2 py-1 text-[11.5px] font-semibold text-primary shadow-sm"
          >
            Open in new tab
          </a>
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
