"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { BellOff, CircleCheck, Copy, TriangleAlert, Wallet } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { formatDateTime } from "@/lib/date";
import { dismissAlert, markAlertRead, markAllAlertsRead, type Alert, type AlertType } from "@/lib/alerts";
import { alertBody, categoryNameLookup } from "@/components/alerts/alert-body";
import type { Category } from "@/lib/categories";

interface AlertListProps {
  initialAlerts: Alert[];
  categories: Category[];
}

type FilterKey = "ALL" | AlertType;

const FILTER_TABS: { key: FilterKey; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "BUDGET_THRESHOLD", label: "Budget" },
  { key: "ANOMALY_HIGH", label: "Anomaly" },
  { key: "LOW_CONFIDENCE", label: "Review" },
  { key: "DUPLICATE_SUSPECTED", label: "Duplicate" },
];

const TYPE_STYLE: Record<
  AlertType,
  { label: string; chipClassName: string; dotClassName: string; icon: typeof Wallet }
> = {
  BUDGET_THRESHOLD: {
    label: "Budget",
    chipClassName: "bg-danger-soft text-danger-foreground",
    dotClassName: "bg-danger",
    icon: Wallet,
  },
  ANOMALY_HIGH: {
    label: "Anomaly",
    chipClassName: "bg-warning-soft text-warning-foreground",
    dotClassName: "bg-warning",
    icon: TriangleAlert,
  },
  LOW_CONFIDENCE: {
    label: "Review",
    chipClassName: "bg-primary/10 text-primary",
    dotClassName: "bg-primary",
    icon: CircleCheck,
  },
  DUPLICATE_SUSPECTED: {
    label: "Duplicate",
    chipClassName: "bg-warning-soft text-warning-foreground",
    dotClassName: "bg-warning",
    icon: Copy,
  },
};

/** DUPLICATE_SUSPECTED's CTA opens the earlier (matched) expense, not the new one carrying the
 * alert — that's the entry the reader needs to compare against. */
function ctaFor(alert: Alert): { label: string; href: string } {
  if (alert.alertType === "BUDGET_THRESHOLD") {
    return { label: "Review budget", href: "/budgets" };
  }
  if (alert.alertType === "DUPLICATE_SUSPECTED" && alert.matchedExpenseId) {
    return { label: "Compare entries", href: `/expenses/${alert.matchedExpenseId}` };
  }
  return { label: "Open expense", href: `/expenses/${alert.expenseId}` };
}

export function AlertList({ initialAlerts, categories }: AlertListProps) {
  const router = useRouter();
  const [alerts, setAlerts] = useState(initialAlerts);
  const [filter, setFilter] = useState<FilterKey>("ALL");
  const [error, setError] = useState<string | null>(null);
  const categoryName = useMemo(() => categoryNameLookup(categories), [categories]);

  const visible = filter === "ALL" ? alerts : alerts.filter((a) => a.alertType === filter);

  async function handleDismiss(alert: Alert) {
    setError(null);
    const previous = alerts;
    setAlerts((current) => current.filter((a) => a.id !== alert.id));
    const result = await dismissAlert(alert.id);
    if (!result.ok) {
      setAlerts(previous);
      setError(result.message);
    } else {
      // The sidebar's unread-alert count is read once by the server-rendered layout and does not
      // otherwise learn this alert left the list until a hard reload.
      router.refresh();
    }
  }

  async function handleOpenCta(alert: Alert) {
    if (alert.read) {
      return;
    }
    setAlerts((current) => current.map((a) => (a.id === alert.id ? { ...a, read: true } : a)));
    const result = await markAlertRead(alert.id);
    if (!result.ok) {
      setAlerts((current) => current.map((a) => (a.id === alert.id ? { ...a, read: false } : a)));
    } else {
      router.refresh();
    }
  }

  async function handleMarkAllRead() {
    setError(null);
    const previous = alerts;
    setAlerts((current) => current.map((a) => ({ ...a, read: true })));
    const result = await markAllAlertsRead();
    if (!result.ok) {
      setAlerts(previous);
      setError(result.message);
    } else {
      router.refresh();
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {error && (
        <div role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2">
          {FILTER_TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setFilter(tab.key)}
              className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                filter === tab.key
                  ? "border-primary/30 bg-primary/10 text-primary"
                  : "border-border bg-card text-muted-foreground hover:text-foreground"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
        {alerts.some((a) => !a.read) && (
          <Button variant="ghost" size="sm" onClick={() => void handleMarkAllRead()}>
            Mark all as read
          </Button>
        )}
      </div>

      {visible.length === 0 ? (
        <Card className="items-center p-8 text-center">
          <BellOff className="size-8 text-muted-foreground" aria-hidden />
          <div className="font-medium">Nothing needs your attention</div>
          <p className="max-w-md text-sm text-muted-foreground">You&apos;re all caught up in this filter.</p>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {visible.map((alert) => {
            const style = TYPE_STYLE[alert.alertType];
            const Icon = style.icon;
            const cta = ctaFor(alert);
            return (
              <Card key={alert.id} data-testid="alert-card" className={`gap-3 p-5 ${alert.read ? "" : "ring-primary/20"}`}>
                <div className="flex items-start gap-3">
                  <span className={`mt-1.5 size-2 shrink-0 rounded-full ${style.dotClassName}`} aria-hidden />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className={`text-sm ${alert.read ? "font-medium" : "font-semibold"}`}>{alert.title}</h2>
                      <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ${style.chipClassName}`}>
                        <Icon className="size-3" aria-hidden /> {style.label}
                      </span>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">{alertBody(alert, categoryName)}</p>
                    <div className="mt-2.5 flex items-center gap-4">
                      <Link
                        href={cta.href}
                        onClick={() => void handleOpenCta(alert)}
                        className="text-xs font-semibold text-primary hover:underline"
                      >
                        {cta.label}
                      </Link>
                      <button
                        type="button"
                        onClick={() => void handleDismiss(alert)}
                        aria-label="Dismiss alert"
                        className="text-xs font-medium text-muted-foreground hover:text-foreground"
                      >
                        Dismiss
                      </button>
                    </div>
                  </div>
                  <span className="shrink-0 text-[11px] whitespace-nowrap text-muted-foreground">
                    {formatDateTime(alert.createdAt)}
                  </span>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
