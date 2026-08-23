"use client";

import { useState } from "react";
import Link from "next/link";
import { ChevronRight, FileText, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { PolicyStatusChip } from "@/components/policies/policy-status-chip";
import { PolicyUploadPanel } from "@/components/policies/policy-upload-panel";
import type { PolicyDocument } from "@/lib/policies";

type FilterKey = "all" | "indexed" | "processing" | "failed";

const FILTER_TABS: { key: FilterKey; label: string }[] = [
  { key: "all", label: "All" },
  { key: "indexed", label: "Indexed" },
  { key: "processing", label: "Processing" },
  { key: "failed", label: "Failed" },
];

function groupOf(document: PolicyDocument): Exclude<FilterKey, "all"> {
  if (document.status === "EMBEDDED") return "indexed";
  if (document.status === "FAILED") return "failed";
  return "processing";
}

interface PolicyListProps {
  initialDocuments: PolicyDocument[];
  loadError: boolean;
}

export function PolicyList({ initialDocuments, loadError }: PolicyListProps) {
  const [documents, setDocuments] = useState(initialDocuments);
  const [filter, setFilter] = useState<FilterKey>("all");
  const [uploadOpen, setUploadOpen] = useState(false);

  if (loadError) {
    return (
      <div className="rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-danger">
        Couldn&apos;t load policy documents. Refresh to try again.
      </div>
    );
  }

  const indexedCount = documents.filter((d) => d.status === "EMBEDDED").length;
  const totalChunks = documents
    .filter((d) => d.status === "EMBEDDED")
    .reduce((sum, d) => sum + d.chunkCount, 0);
  const failedCount = documents.filter((d) => d.status === "FAILED").length;

  const filtered = filter === "all" ? documents : documents.filter((d) => groupOf(d) === filter);

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-1.5">
          {FILTER_TABS.map((tab) => {
            const count = tab.key === "all" ? documents.length : documents.filter((d) => groupOf(d) === tab.key).length;
            const active = filter === tab.key;
            return (
              <button
                key={tab.key}
                type="button"
                onClick={() => setFilter(tab.key)}
                className={cn(
                  "rounded-lg border px-3 py-1.5 text-[12.5px] font-medium whitespace-nowrap",
                  active
                    ? "border-accent-soft bg-accent-soft text-accent-foreground"
                    : "border-border bg-card text-muted-foreground",
                )}
              >
                {tab.label} {count}
              </button>
            );
          })}
        </div>
        <button
          type="button"
          onClick={() => setUploadOpen((open) => !open)}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-primary px-3.5 py-2 text-[12.5px] font-semibold whitespace-nowrap text-primary-foreground"
        >
          <Plus className="size-3.5" aria-hidden />
          Upload policy PDF
        </button>
      </div>

      {uploadOpen && (
        <PolicyUploadPanel
          onClose={() => setUploadOpen(false)}
          onUploaded={(document) =>
            setDocuments((current) => [document, ...current.filter((d) => d.id !== document.id)])
          }
        />
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Documents indexed" value={String(indexedCount)} note={`of ${documents.length} uploaded`} />
        <StatCard label="Passages indexed" value={totalChunks.toLocaleString("en-US")} note="retrievable across the org" />
        <StatCard
          label="Failed"
          value={String(failedCount)}
          note={failedCount ? "needs re-upload" : "nothing to fix"}
          tone={failedCount ? "danger" : "default"}
        />
      </div>

      <div className="flex flex-col gap-2.5">
        {filtered.map((document) => (
          <PolicyRow key={document.id} document={document} />
        ))}
      </div>

      {filtered.length === 0 && documents.length > 0 && (
        <div className="rounded-xl border border-border bg-card px-6 py-14 text-center">
          <div className="text-[14px] font-semibold">No policies in this filter</div>
          <div className="mt-1 text-[12.5px] text-muted-foreground">Choose a different filter to see more.</div>
        </div>
      )}

      {documents.length === 0 && (
        <div className="rounded-xl border border-border bg-card px-6 py-14 text-center">
          <FileText className="mx-auto mb-3 size-6 text-muted-foreground" aria-hidden />
          <div className="text-[14px] font-semibold">No policy documents yet</div>
          <div className="mx-auto mt-1.5 max-w-[46ch] text-[12.5px] leading-[1.6] text-muted-foreground">
            Upload your expense policy as a PDF. Ledgerly splits it into passages and retrieves the
            nearest one whenever it categorizes an expense — the citation shown on an expense
            detail screen is always a real quote from a document you uploaded here.
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({
  label,
  value,
  note,
  tone = "default",
}: {
  label: string;
  value: string;
  note: string;
  tone?: "default" | "danger";
}) {
  return (
    <div className="rounded-xl border border-border bg-card px-[17px] py-[15px] shadow-sm">
      <div className="text-[11.5px] font-medium tracking-[0.05em] text-muted-foreground uppercase">{label}</div>
      <div className={cn("mt-[7px] text-2xl font-semibold tracking-tight", tone === "danger" && "text-danger")}>
        {value}
      </div>
      <div className="mt-[3px] text-xs text-muted-foreground">{note}</div>
    </div>
  );
}

function PolicyRow({ document }: { document: PolicyDocument }) {
  const chunkLabel =
    document.status === "EMBEDDED"
      ? `${document.chunkCount} ${document.chunkCount === 1 ? "chunk" : "chunks"}`
      : "—";
  const metaLine =
    document.status === "EMBEDDED"
      ? `${document.chunkCount} passages indexed and searchable`
      : document.status === "FAILED"
        ? "0 passages — not searchable"
        : "passages appear when embedding completes";

  return (
    <Link
      href={`/policies/${document.id}`}
      className="flex items-center gap-3.5 rounded-xl border border-border bg-card px-[18px] py-4 shadow-sm transition-colors hover:bg-muted/40"
    >
      <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="truncate font-mono text-[13px] font-medium">{document.filename}</div>
        <div className="mt-0.5 text-[11.5px] text-muted-foreground">{metaLine}</div>
        {document.status === "FAILED" && document.failureReason && (
          <div className="mt-2 rounded-lg border border-danger/30 bg-danger-soft px-2.5 py-1.5 font-mono text-[11px] break-all text-danger">
            {document.failureReason}
          </div>
        )}
      </div>
      <PolicyStatusChip status={document.status} />
      <div className="hidden shrink-0 font-mono text-[12px] text-muted-foreground tabular-nums sm:block">
        {chunkLabel}
      </div>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
    </Link>
  );
}
