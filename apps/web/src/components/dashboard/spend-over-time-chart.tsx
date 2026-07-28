import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import type { MonthlySpend } from "@/lib/dashboard";

interface SpendOverTimeChartProps {
  series: MonthlySpend[];
  currency: string;
}

const CHART_WIDTH = 480;
const CHART_HEIGHT = 150;
const PADDING = 10;

// Fixed rather than the system locale — same reasoning as money.ts's DISPLAY_LOCALE: the design
// is locale-invariant ("Feb", "Mar", ... in the handoff), and letting Intl pick up whatever
// locale the deploying machine has would silently change month labels per-environment.
const MONTH_LABEL_LOCALE = "en-US";

function monthLabel(month: string): string {
  const [year, monthNum] = month.split("-").map(Number);
  return new Date(Date.UTC(year, monthNum - 1, 1)).toLocaleDateString(MONTH_LABEL_LOCALE, {
    month: "short",
    timeZone: "UTC",
  });
}

export function SpendOverTimeChart({ series, currency }: SpendOverTimeChartProps) {
  if (series.length === 0) {
    return (
      <Card className="p-[22px_24px]">
        <div className="mb-2.5 text-[12.5px] font-medium text-muted-foreground">
          Spend over time
        </div>
        <div className="text-sm text-muted-foreground">No spend history yet.</div>
      </Card>
    );
  }

  const max = Math.max(...series.map((m) => m.amountMinor), 1);
  const stepX = (CHART_WIDTH - PADDING * 2) / Math.max(series.length - 1, 1);
  const points = series.map((m, i) => ({
    x: PADDING + i * stepX,
    y: PADDING + (CHART_HEIGHT - PADDING * 2) * (1 - m.amountMinor / max),
    month: m,
  }));
  const polylinePoints = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");

  return (
    <Card className="p-[22px_24px]">
      <div className="mb-2.5 text-[12.5px] font-medium text-muted-foreground">
        Spend over time
      </div>
      <svg
        width="100%"
        height="150"
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Monthly spend from ${monthLabel(series[0].month)} to ${monthLabel(series[series.length - 1].month)}: ${series
          .map((m) => `${monthLabel(m.month)} ${formatMoney(m.amountMinor, currency)}`)
          .join(", ")}`}
      >
        <polyline
          points={polylinePoints}
          fill="none"
          stroke="var(--primary)"
          strokeWidth="2.5"
        />
        {points.map((p) => (
          <circle key={p.month.month} cx={p.x} cy={p.y} r="3" fill="var(--primary)">
            <title>
              {monthLabel(p.month.month)}: {formatMoney(p.month.amountMinor, currency)}
            </title>
          </circle>
        ))}
      </svg>
      <div className="mt-1 flex justify-between text-[11px] text-muted-foreground">
        {series.map((m) => (
          <span key={m.month}>{monthLabel(m.month)}</span>
        ))}
      </div>
      <table className="sr-only">
        <caption>Monthly spend</caption>
        <thead>
          <tr>
            <th scope="col">Month</th>
            <th scope="col">Amount</th>
          </tr>
        </thead>
        <tbody>
          {series.map((m) => (
            <tr key={m.month}>
              <td>{monthLabel(m.month)}</td>
              <td>{formatMoney(m.amountMinor, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
