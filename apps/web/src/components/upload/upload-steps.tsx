import { Check } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ConnectionState, DocumentStatusState } from "@/components/upload/use-document-status";

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
function buildSteps(documentStatus: DocumentStatusState["status"], failed: boolean): Step[] {
  const uploadingDone = documentStatus !== null;
  const processingDone = documentStatus === "EXTRACTED" || documentStatus === "NEEDS_REVIEW" || documentStatus === "FAILED";

  return [
    { label: "Uploading", state: uploadingDone ? "done" : "active" },
    {
      label: "Processing",
      state: failed
        ? "failed"
        : processingDone
          ? "done"
          : uploadingDone
            ? "active"
            : "pending",
    },
    {
      label: failed ? "Failed" : "Complete",
      state: failed ? "failed" : processingDone ? "done" : "pending",
    },
  ];
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
  documentStatus: DocumentStatusState["status"];
  failed: boolean;
  connection: ConnectionState;
}

export function UploadSteps({ filename, sizeLabel, documentStatus, failed, connection }: UploadStepsProps) {
  const steps = buildSteps(documentStatus, failed);

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
