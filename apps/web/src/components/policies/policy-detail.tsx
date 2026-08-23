"use client";

import { useMemo, useState } from "react";
import { Search, X } from "lucide-react";
import { PolicyStatusChip } from "@/components/policies/policy-status-chip";
import type { PolicyChunk, PolicyDocument } from "@/lib/policies";

const CHUNKS_PER_PAGE = 6;

interface PolicyDetailProps {
  document: PolicyDocument;
  chunks: PolicyChunk[];
}

export function PolicyDetail({ document, chunks }: PolicyDetailProps) {
  const [query, setQuery] = useState("");
  const [shown, setShown] = useState(CHUNKS_PER_PAGE);

  const matched = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? chunks.filter((chunk) => chunk.text.toLowerCase().includes(q)) : chunks;
  }, [chunks, query]);

  const visible = matched.slice(0, shown);
  const remaining = Math.max(0, matched.length - visible.length);

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <div className="flex flex-wrap items-center gap-2.5">
          <div className="font-mono text-lg font-semibold break-all">{document.filename}</div>
          <PolicyStatusChip status={document.status} />
        </div>
        <div className="mt-[7px] text-[12.5px] text-muted-foreground">
          {document.status === "EMBEDDED"
            ? `Uploaded ${formatDate(document.createdAt)} · ${document.chunkCount} passages indexed and searchable`
            : document.status === "FAILED"
              ? `Uploaded ${formatDate(document.createdAt)} · 0 passages — not searchable`
              : `Uploaded ${formatDate(document.createdAt)} · passages appear when embedding completes`}
        </div>
      </div>

      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[1fr_300px]">
        <div className="min-w-0">
          {document.status === "EMBEDDED" && (
            <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
              <div className="flex flex-wrap items-center gap-3 border-b border-border px-[18px] py-[15px]">
                <div className="text-[11.5px] font-semibold whitespace-nowrap text-muted-foreground uppercase">
                  Indexed passages
                </div>
                <div className="flex min-w-[150px] flex-1 items-center gap-2 rounded-lg border border-border bg-background px-2.5 py-1.5">
                  <Search className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                  <input
                    value={query}
                    onChange={(event) => {
                      setQuery(event.target.value);
                      setShown(CHUNKS_PER_PAGE);
                    }}
                    placeholder="Search this document — try meals, mileage, receipts"
                    className="min-w-0 flex-1 border-none bg-transparent text-[12.5px] outline-none"
                  />
                  {query && (
                    <button
                      type="button"
                      onClick={() => setQuery("")}
                      aria-label="Clear search"
                      className="shrink-0 text-muted-foreground"
                    >
                      <X className="size-3.5" aria-hidden />
                    </button>
                  )}
                </div>
                <div className="text-[11.5px] whitespace-nowrap text-muted-foreground">
                  {matched.length} {matched.length === 1 ? "match" : "matches"}
                </div>
              </div>

              {visible.map((chunk) => (
                <div
                  key={chunk.index}
                  className="flex gap-4 border-b border-border px-[18px] py-[18px] last:border-b-0"
                >
                  <div className="w-6 shrink-0 pt-0.5 font-mono text-[11.5px] font-medium text-muted-foreground tabular-nums">
                    {String(chunk.index).padStart(2, "0")}
                  </div>
                  <div className="min-w-0 max-w-[66ch] text-[13.5px] leading-[1.68] text-foreground/90">
                    {chunk.text}
                  </div>
                </div>
              ))}

              {matched.length === 0 && (
                <div className="px-5 py-11 text-center">
                  <div className="text-[13.5px] font-semibold">No passage matches &quot;{query}&quot;</div>
                  <div className="mt-1.5 text-[12.5px] text-muted-foreground">
                    The AI can only quote text that exists in the document.
                  </div>
                </div>
              )}

              {remaining > 0 && (
                <button
                  type="button"
                  onClick={() => setShown((count) => count + CHUNKS_PER_PAGE)}
                  className="w-full bg-background/60 px-[18px] py-[15px] text-center text-[12.5px] font-semibold text-accent-foreground"
                >
                  Show {remaining} more
                </button>
              )}
            </div>
          )}

          {document.status === "FAILED" && (
            <div className="rounded-xl border border-danger/30 bg-card p-6 shadow-sm">
              <div className="text-[14.5px] font-semibold">Embedding failed — no passages were stored</div>
              <p className="mt-2.5 max-w-[62ch] text-[13px] leading-[1.6] text-muted-foreground">
                This document produced zero passages, so nothing in it can be retrieved or quoted.
                It is not partially indexed. Re-uploading the same file re-runs the split and embed
                step.
              </p>
              {document.failureReason && (
                <div className="mt-3.5 rounded-lg border border-danger/30 bg-danger-soft px-3 py-2.5 font-mono text-xs break-all text-danger">
                  {document.failureReason}
                </div>
              )}
            </div>
          )}

          {(document.status === "PENDING" || document.status === "PROCESSING") && (
            <div className="rounded-xl border border-border bg-card px-6 py-11 text-center shadow-sm">
              <span
                className="mx-auto mb-3.5 block size-[22px] animate-spin rounded-full border-2 border-muted border-t-primary"
                aria-hidden
              />
              <div className="text-[14px] font-semibold">
                {document.status === "PROCESSING" ? "Splitting and embedding" : "Queued for embedding"}
              </div>
              <p className="mx-auto mt-1.5 max-w-[44ch] text-[12.5px] leading-[1.55] text-muted-foreground">
                {document.status === "PROCESSING"
                  ? "The document is being split into passages and each one embedded. Nothing is retrievable until this finishes."
                  : "This document is waiting for an embedding worker. It has not been split yet."}
              </p>
            </div>
          )}
        </div>

        <div className="flex flex-col gap-3.5">
          <div className="rounded-xl border border-border bg-card p-[18px] shadow-sm">
            <div className="text-[11.5px] font-semibold text-muted-foreground uppercase">Document</div>
            <div className="mt-3.5 flex flex-col gap-[11px]">
              <Fact label="Filename" value={document.filename} mono />
              <Fact label="Status" value={document.status} mono danger={document.status === "FAILED"} />
              <Fact label="Uploaded" value={formatDate(document.createdAt)} />
              <Fact label="Passages" value={document.status === "EMBEDDED" ? String(document.chunkCount) : "0"} mono />
            </div>
          </div>

          <div className="rounded-xl border border-border bg-background/50 p-4">
            <div className="text-[12.5px] font-semibold">How this text is used</div>
            <p className="mt-1.5 text-[12.5px] leading-[1.6] text-muted-foreground">
              When an expense is categorized, Ledgerly retrieves the passages closest to it and may
              quote one as the reason. A quote is checked against the stored passage before it is
              saved, so anything you see cited is text from this document.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function Fact({ label, value, mono, danger }: { label: string; value: string; mono?: boolean; danger?: boolean }) {
  return (
    <div>
      <div className="text-[11.5px] text-muted-foreground">{label}</div>
      <div
        className={`mt-[3px] text-[12.5px] font-medium break-all tabular-nums ${mono ? "font-mono" : ""} ${danger ? "text-danger" : ""}`}
      >
        {value}
      </div>
    </div>
  );
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleDateString("en-US", { month: "short", day: "2-digit", year: "numeric" });
}
