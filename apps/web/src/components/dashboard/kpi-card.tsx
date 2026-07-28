import { ArrowUp, ArrowDown, Sparkles } from "lucide-react";
import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import type { CurrencyTotal, MonthlySpend } from "@/lib/dashboard";

interface KpiCardProps {
  totalsThisMonth: CurrencyTotal[];
  totalsLastMonth: CurrencyTotal[];
  monthlySeries: MonthlySpend[];
}

function totalFor(totals: CurrencyTotal[], currency: string): number {
  return totals.find((t) => t.currency === currency)?.amountMinor ?? 0;
}

/** 90×30 mini trend line from the trailing months of `monthlySeries`, in the KPI card's own
 * currency — a real (if coarse) signal, not a decorative fabrication. */
function sparklinePoints(series: MonthlySpend[]): string {
  if (series.length === 0) {
    return "";
  }
  const max = Math.max(...series.map((m) => m.amountMinor), 1);
  const stepX = 160 / Math.max(series.length - 1, 1);
  return series
    .map((m, i) => {
      const x = i * stepX;
      const y = 36 - (m.amountMinor / max) * 32;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

export function KpiCard({ totalsThisMonth, totalsLastMonth, monthlySeries }: KpiCardProps) {
  if (totalsThisMonth.length === 0) {
    return (
      <Card data-testid="kpi-card" className="p-[22px_24px]">
        <div className="text-[12.5px] font-medium whitespace-nowrap text-muted-foreground">
          Total spend this month
        </div>
        <div className="mt-4 text-sm text-muted-foreground">No expenses posted yet this month.</div>
      </Card>
    );
  }

  return (
    <Card data-testid="kpi-card" className="gap-0 p-[22px_24px]">
      {totalsThisMonth.map((total, index) => {
        const lastMonthAmount = totalFor(totalsLastMonth, total.currency);
        const hasComparison = lastMonthAmount > 0;
        const deltaPct = hasComparison
          ? Math.round(((total.amountMinor - lastMonthAmount) / lastMonthAmount) * 100)
          : null;
        const isUp = (deltaPct ?? 0) >= 0;
        const points = sparklinePoints(monthlySeries);

        return (
          <div key={total.currency} className={index > 0 ? "mt-5 border-t border-border pt-5" : ""}>
            <div className="flex items-start justify-between">
              <div className="text-[12.5px] font-medium whitespace-nowrap text-muted-foreground">
                Total spend this month{totalsThisMonth.length > 1 ? ` (${total.currency})` : ""}
              </div>
              {points && (
                <svg
                  width="90"
                  height="30"
                  viewBox="0 0 160 40"
                  preserveAspectRatio="none"
                  className="ml-3 shrink-0"
                  role="img"
                  aria-label={`Spend trend over the trailing ${monthlySeries.length} months`}
                >
                  <polyline
                    points={points}
                    fill="none"
                    stroke="var(--primary)"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              )}
            </div>

            <div className="mt-1 flex items-baseline gap-3">
              <div className="font-mono text-[38px] leading-none font-semibold tracking-[-0.02em] tabular-nums">
                {formatMoney(total.amountMinor, total.currency)}
              </div>
              {deltaPct !== null && (
                <div
                  className="flex items-center gap-1 rounded-md bg-success-soft px-2 py-[3px] text-[13px] font-semibold text-success-foreground"
                  aria-label={`${isUp ? "Up" : "Down"} ${Math.abs(deltaPct)}% vs last month`}
                >
                  {isUp ? (
                    <ArrowUp className="size-3" aria-hidden />
                  ) : (
                    <ArrowDown className="size-3" aria-hidden />
                  )}
                  {Math.abs(deltaPct)}%
                </div>
              )}
            </div>

            <div className="mt-1 text-xs text-muted-foreground">
              {hasComparison
                ? `vs ${formatMoney(lastMonthAmount, total.currency)} last month`
                : "No spend last month to compare against"}
            </div>
          </div>
        );
      })}

      {totalsThisMonth.length === 1 && totalFor(totalsLastMonth, totalsThisMonth[0].currency) > 0 && (
        <InsightCallout
          thisMonth={totalsThisMonth[0]}
          lastMonthAmount={totalFor(totalsLastMonth, totalsThisMonth[0].currency)}
        />
      )}
    </Card>
  );
}

function InsightCallout({
  thisMonth,
  lastMonthAmount,
}: {
  thisMonth: CurrencyTotal;
  lastMonthAmount: number;
}) {
  const deltaPct = Math.round(((thisMonth.amountMinor - lastMonthAmount) / lastMonthAmount) * 100);
  if (deltaPct === 0) {
    return null;
  }
  const direction = deltaPct > 0 ? "up" : "down";
  return (
    <div className="mt-4 flex gap-2 rounded-lg bg-accent-soft p-[11px_13px] text-[12.5px] text-accent-foreground">
      <Sparkles className="mt-px size-[15px] shrink-0" aria-hidden />
      <span>
        Spend {direction} {Math.abs(deltaPct)}% compared to last month.
      </span>
    </div>
  );
}
