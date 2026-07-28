import Link from "next/link";
import { Card } from "@/components/ui/card";

interface SummaryCardProps {
  reviewQueueCount: number;
  documentsProcessedToday: number;
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <div className="text-[12.5px] font-medium whitespace-nowrap text-muted-foreground">
        {label}
      </div>
      <div className="ml-3 shrink-0 font-mono text-[13px] font-semibold whitespace-nowrap">
        {value}
      </div>
    </div>
  );
}

export function SummaryCard({ reviewQueueCount, documentsProcessedToday }: SummaryCardProps) {
  return (
    <Card className="flex flex-col gap-4 p-[22px_24px]">
      <Row label="Review queue" value={`${reviewQueueCount} item${reviewQueueCount === 1 ? "" : "s"}`} />
      <Row label="Documents processed today" value={documentsProcessedToday} />
      <Link
        href="/review"
        className="mt-auto rounded-lg bg-primary py-2 text-center text-[12.5px] font-semibold text-primary-foreground transition-all hover:-translate-y-px hover:shadow-sm"
      >
        Go to review queue
      </Link>
    </Card>
  );
}
