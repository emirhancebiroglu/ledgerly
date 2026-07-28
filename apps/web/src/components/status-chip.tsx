import { CircleCheck, CircleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

export type ExpenseStatus = "POSTED" | "NEEDS_REVIEW";

const STATUS_CONFIG: Record<
  ExpenseStatus,
  { label: string; icon: typeof CircleCheck; className: string }
> = {
  POSTED: {
    label: "Posted",
    icon: CircleCheck,
    className: "bg-success-soft text-success-foreground",
  },
  NEEDS_REVIEW: {
    label: "Needs review",
    icon: CircleAlert,
    className: "bg-warning-soft text-warning-foreground",
  },
};

interface StatusChipProps {
  status: ExpenseStatus;
  className?: string;
}

/** Every status is distinguished by icon as well as color (docs/design/m7/README.md's
 * colorblind-safe requirement) — never color alone. */
export function StatusChip({ status, className }: StatusChipProps) {
  const config = STATUS_CONFIG[status];
  const Icon = config.icon;
  return (
    <span
      className={cn(
        "inline-flex w-fit shrink-0 items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold whitespace-nowrap",
        config.className,
        className,
      )}
    >
      <Icon className="size-3" aria-hidden />
      {config.label}
    </span>
  );
}
