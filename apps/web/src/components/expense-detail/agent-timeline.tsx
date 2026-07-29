import { CircleCheck, CircleDashed, TriangleAlert } from "lucide-react";
import { Card } from "@/components/ui/card";
import { formatDateTime } from "@/lib/date";
import type { DocumentActivity, DocumentActivityStage } from "@/lib/expense-detail";

const labels: Record<DocumentActivityStage, string> = {
  UPLOADED: "Uploaded",
  EXTRACTING: "Extracting document data",
  CATEGORIZING: "Categorizing expense",
  DRAFTING_LEDGER: "Drafting ledger entries",
  POSTED: "Posted to ledger",
  NEEDS_REVIEW: "Needs review",
  EXTRACTION_NEEDS_REVIEW: "Extraction needs review",
  FAILED: "Processing failed",
  CATEGORIZATION_FAILED: "Categorization could not be completed",
};

function isFlagged(stage: DocumentActivityStage) {
  return (
    stage === "NEEDS_REVIEW" ||
    stage === "EXTRACTION_NEEDS_REVIEW" ||
    stage === "FAILED" ||
    stage === "CATEGORIZATION_FAILED"
  );
}

export function AgentTimeline({ activity }: { activity: DocumentActivity[] }) {
  return (
    <Card className="p-[22px_24px]">
      <div className="mb-4 text-[12.5px] font-medium text-muted-foreground">Agent activity</div>
      {activity.length === 0 ? (
        <p className="text-sm text-muted-foreground">No agent activity has been recorded yet.</p>
      ) : (
        <ol className="flex flex-col">
          {activity.map((step, index) => {
            const flagged = isFlagged(step.stage);
            return (
              <li key={step.id} className="flex gap-3">
                <div className="flex flex-col items-center">
                  {flagged ? <TriangleAlert className="mt-0.5 size-3 text-warning" aria-hidden /> : step.stage === "POSTED" ? <CircleCheck className="mt-0.5 size-3 text-success" aria-hidden /> : <CircleDashed className="mt-0.5 size-3 text-muted-foreground" aria-hidden />}
                  {index < activity.length - 1 && <span className="w-px flex-1 bg-border" aria-hidden />}
                </div>
                <div className={`min-w-0 pb-4 ${index === activity.length - 1 ? "pb-0" : ""}`}>
                  <div className="flex flex-wrap items-baseline gap-x-2"><span className="text-[13px] font-medium">{labels[step.stage]}</span>{flagged && <span className="text-[11px] font-semibold text-warning-foreground">Flagged</span>}</div>
                  <div className="font-mono text-[11px] text-muted-foreground">{formatDateTime(step.createdAt)}</div>
                  {step.detail && <div className="mt-0.5 text-[12.5px] text-muted-foreground">{step.detail}</div>}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}
