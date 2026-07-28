import Link from "next/link";
import { AlertCircle, BellRing, TriangleAlert } from "lucide-react";
import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import type { AlertSummary } from "@/lib/dashboard";

export function RecentAlerts({ alerts, totalCount }: { alerts: AlertSummary[]; totalCount: number }) {
  return (
    <Card className="gap-0 py-1.5" data-testid="recent-alerts">
      <div className="flex items-center justify-between px-6 pt-3.5 pb-2.5">
        <div><div className="text-[12.5px] font-medium text-muted-foreground">Recent alerts</div><div className="mt-0.5 text-xs text-muted-foreground">{totalCount} alert{totalCount === 1 ? "" : "s"} recorded</div></div>
        <BellRing className="size-4 text-muted-foreground" aria-hidden />
      </div>
      {alerts.length === 0 ? (
        <div className="px-6 py-6 text-sm text-muted-foreground">No budget or anomaly alerts yet.</div>
      ) : alerts.map((alert) => {
        const isAnomaly = alert.alertType === "ANOMALY_HIGH";
        const Icon = isAnomaly ? AlertCircle : TriangleAlert;
        const amount = alert.spentMinor !== null && alert.limitMinor !== null
          ? `${formatMoney(alert.spentMinor, alert.currency)} of ${formatMoney(alert.limitMinor, alert.currency)}`
          : alert.budgetBurnRate !== null ? `${Math.round(alert.budgetBurnRate * 100)}% budget burn` : "Validated anomaly";
        const title = isAnomaly ? "Unusual expense" : `${alert.thresholdPercent}% budget threshold`;
        const description = alert.explanation ?? (isAnomaly ? "Validated high-risk anomaly" : "Threshold crossed for this monthly budget");
        return <Link key={alert.id} href={`/expenses/${alert.expenseId}`} className="flex items-start gap-3 border-t border-border/60 px-6 py-3 transition-colors hover:bg-muted/60">
          <span className={`mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full ${isAnomaly ? "bg-danger-soft text-danger" : "bg-warning-soft text-warning-foreground"}`}><Icon className="size-3.5" aria-hidden /></span>
          <span className="min-w-0 flex-1"><span className="block text-[13px] font-medium">{title}</span><span className="block truncate text-xs text-muted-foreground">{description}</span></span>
          <span className="shrink-0 font-mono text-xs tabular-nums text-muted-foreground">{amount}</span>
        </Link>;
      })}
    </Card>
  );
}
