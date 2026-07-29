import { Check } from "lucide-react";
import { cn } from "@/lib/utils";
import type { DocumentActivity, DocumentActivityStage } from "@/lib/expense-detail";
import type { ConnectionState } from "@/components/upload/use-document-status";

type StepState = "pending" | "active" | "done" | "failed";

interface Step {
  label: string;
  state: StepState;
}

/**
 * The handoff specs 5 discrete steps (Uploading → OCR → Matching → Drafting → Complete), but the
 * api's real DocumentStatus only distinguishes PENDING/PROCESSING from a terminal outcome — there
 * is no event for "OCR done" vs "matching done" to drive intermediate steps honestly. Collapsed
 * to the 3 states the api actually reports rather than fabricating progress within PROCESSING.
 */
const STAGES: Array<[DocumentActivityStage, string]> = [["UPLOADED", "Uploading"], ["EXTRACTING", "Extracting document data"], ["CATEGORIZING", "Categorizing expense"], ["DRAFTING_LEDGER", "Drafting ledger entries"]];
function buildSteps(activity: DocumentActivity[]): Step[] {
  const observed = new Set(activity.map((item) => item.stage));
  const terminal = activity.find((item) => ["POSTED", "NEEDS_REVIEW", "FAILED", "CATEGORIZATION_FAILED"].includes(item.stage));
  const firstPending = STAGES.findIndex(([stage]) => !observed.has(stage));
  return [...STAGES.map(([stage, label], index) => ({ label, state: observed.has(stage) ? "done" as StepState : index === firstPending ? "active" as StepState : "pending" as StepState })), { label: terminal?.stage === "POSTED" ? "Posted to ledger" : terminal?.stage === "NEEDS_REVIEW" ? "Needs review" : terminal ? "Failed" : "Outcome", state: terminal ? (terminal.stage === "POSTED" || terminal.stage === "NEEDS_REVIEW" ? "done" : "failed") : firstPending === -1 ? "active" : "pending" }];
}

function StepIndicator({ state }: { state: StepState }) {
  if (state === "done") {
    return (
      <div className="flex size-[18px] shrink-0 items-center justify-center rounded-full bg-success">
        <Check className="size-2.5 text-white" strokeWidth={3} aria-hidden />
      </div>
    );
  }
  if (state === "failed") {
    return (
      <div className="flex size-[18px] shrink-0 items-center justify-center rounded-full bg-danger">
        <span className="text-[10px] font-bold text-white" aria-hidden>
          !
        </span>
      </div>
    );
  }
  if (state === "active") {
    return (
      <div
        className="size-[18px] shrink-0 animate-spin rounded-full border-2 border-primary border-t-transparent"
        role="status"
        aria-label="In progress"
      />
    );
  }
  return <div className="size-[18px] shrink-0 rounded-full border-2 border-border" aria-hidden />;
}

interface UploadStepsProps {
  filename: string;
  sizeLabel: string;
  activity: DocumentActivity[];
  connection: ConnectionState;
}

export function UploadSteps({ filename, sizeLabel, activity, connection }: UploadStepsProps) {
  const steps = buildSteps(activity);

  return (
    <div className="rounded-xl border border-border bg-card p-[18px_20px] shadow-[0_1px_2px_oklch(0.2_0.02_265_/_0.04),0_8px_20px_oklch(0.2_0.02_265_/_0.03)]">
      <div className="mb-3.5 flex items-center justify-between">
        <div className="truncate text-[13px] font-semibold">{filename}</div>
        <div className="shrink-0 text-[11.5px] text-muted-foreground">{sizeLabel}</div>
      </div>
      <div className="flex flex-col gap-3">
        {steps.map((step) => (
          <div
            key={step.label}
            className={cn("flex items-center gap-2.5", step.state === "pending" && "opacity-45")}
          >
            <StepIndicator state={step.state} />
            <div className="text-[13px] font-medium">{step.label}</div>
          </div>
        ))}
      </div>
      {connection === "stalled" && (
        <p className="mt-3.5 text-[12px] text-warning-foreground" role="alert">
          Lost connection to the live status stream — retrying…
        </p>
      )}
    </div>
  );
}
