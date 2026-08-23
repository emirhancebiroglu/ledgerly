import { CircleCheck, CircleAlert, Clock, LoaderCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import type { PolicyDocumentStatus } from "@/lib/policies";

const STATUS_CONFIG: Record<
  PolicyDocumentStatus,
  { label: string; icon: typeof CircleCheck; className: string; spin?: boolean }
> = {
  EMBEDDED: {
    label: "Indexed",
    icon: CircleCheck,
    className: "bg-success-soft text-success-foreground",
  },
  PROCESSING: {
    label: "Processing",
    icon: LoaderCircle,
    className: "bg-accent-soft text-accent-foreground",
    spin: true,
  },
  PENDING: {
    label: "Queued",
    icon: Clock,
    className: "bg-muted text-muted-foreground",
  },
  FAILED: {
    label: "Failed",
    icon: CircleAlert,
    className: "bg-danger-soft text-danger-foreground",
  },
};

interface PolicyStatusChipProps {
  status: PolicyDocumentStatus;
  className?: string;
}

/** Every status is distinguished by icon as well as color — never color alone. Maps the API's
 * four raw enum values to operator-facing labels; the raw value is still shown verbatim
 * elsewhere (the detail screen's Document facts card) so this mapping never hides it. */
export function PolicyStatusChip({ status, className }: PolicyStatusChipProps) {
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
      <Icon className={cn("size-3", config.spin && "animate-spin")} aria-hidden />
      {config.label}
    </span>
  );
}
