import { Card } from "@/components/ui/card";
import type { DocumentMeta } from "@/lib/expense-detail";
import type { ExpenseStatus } from "@/components/status-chip";

const DATETIME_LOCALE = "en-US";

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString(DATETIME_LOCALE, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

interface TimelineStep {
  label: string;
  detail: string;
  timestamp: string;
  flagged: boolean;
}

/**
 * There is no persisted event log for a document — only a live Redis pub/sub stream (M7a T6)
 * that a client already listening catches and nothing else does; a document opened after it
 * finished processing has no history to replay. Rather than fabricate the handoff's full 5-step
 * sequence, this derives the 2-3 steps that are actually known from `DocumentMeta` and the
 * expense's own status.
 */
function buildSteps(document: DocumentMeta, expenseStatus: ExpenseStatus): TimelineStep[] {
  const steps: TimelineStep[] = [
    {
      label: "Uploaded",
      detail: document.filename,
      timestamp: document.createdAt,
      flagged: false,
    },
  ];

  if (document.status === "FAILED") {
    steps.push({
      label: "Processing failed",
      detail: document.failureReason ?? "No further detail available.",
      timestamp: document.createdAt,
      flagged: true,
    });
    return steps;
  }

  if (document.status === "EXTRACTED" || document.status === "NEEDS_REVIEW") {
    steps.push({
      label: "Document processed",
      detail: "Extraction and categorization complete.",
      timestamp: document.createdAt,
      flagged: false,
    });
  }

  if (expenseStatus === "NEEDS_REVIEW") {
    steps.push({
      label: "Flagged for review",
      detail: "Confidence below the auto-post threshold.",
      timestamp: document.createdAt,
      flagged: true,
    });
  }

  return steps;
}

interface AgentTimelineProps {
  document: DocumentMeta;
  expenseStatus: ExpenseStatus;
}

export function AgentTimeline({ document, expenseStatus }: AgentTimelineProps) {
  const steps = buildSteps(document, expenseStatus);

  return (
    <Card className="p-[22px_24px]">
      <div className="mb-4 text-[12.5px] font-medium text-muted-foreground">Agent activity</div>
      <ol className="flex flex-col">
        {steps.map((step, index) => (
          <li key={index} className="flex gap-3">
            <div className="flex flex-col items-center">
              <span
                className={`mt-1 size-2.5 shrink-0 rounded-full ${
                  step.flagged ? "bg-warning" : "bg-muted-foreground/40"
                }`}
                aria-hidden
              />
              {index < steps.length - 1 && (
                <span className="w-px flex-1 bg-border" aria-hidden />
              )}
            </div>
            <div className={`min-w-0 pb-4 ${index === steps.length - 1 ? "pb-0" : ""}`}>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-[13px] font-medium">{step.label}</span>
                {step.flagged && (
                  <span className="text-[11px] font-semibold text-warning-foreground">
                    Flagged
                  </span>
                )}
              </div>
              <div className="font-mono text-[11px] text-muted-foreground">
                {formatTimestamp(step.timestamp)}
              </div>
              <div className="mt-0.5 text-[12.5px] text-muted-foreground">{step.detail}</div>
            </div>
          </li>
        ))}
      </ol>
    </Card>
  );
}
