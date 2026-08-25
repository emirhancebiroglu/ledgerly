import { getDashboardSummary } from "@/lib/dashboard";
import { listExpenses } from "@/lib/expenses";
import { listCategories, categoryNameLookup } from "@/lib/categories";
import { KpiCard } from "@/components/dashboard/kpi-card";
import { SummaryCard } from "@/components/dashboard/summary-card";
import { CategoryBreakdown } from "@/components/dashboard/category-breakdown";
import { SpendOverTimeChart } from "@/components/dashboard/spend-over-time-chart";
import { RecentExpenses } from "@/components/dashboard/recent-expenses";

export default async function DashboardPage() {
  const [summary, recentExpensesResult, categories] = await Promise.all([
    getDashboardSummary(),
    listExpenses({ sort: "date,desc", size: 5 }),
    listCategories(),
  ]);
  const recentExpenses = recentExpensesResult.ok ? recentExpensesResult.expenses : [];

  if (!summary) {
    return (
      <div className="max-w-[1080px] p-6 md:p-8">
        <div className="rounded-xl border border-border bg-card p-8 text-center text-sm text-muted-foreground">
          Couldn&apos;t load the dashboard. Try refreshing the page.
        </div>
      </div>
    );
  }

  const categoryName = categoryNameLookup(categories);
  const hasSpendData = summary.totalsThisMonth.length > 0 || summary.totalsLastMonth.length > 0;

  return (
    <div className="flex max-w-[1080px] flex-col gap-5 p-6 md:p-8">
      <div className="grid grid-cols-1 gap-5 shell:grid-cols-[1.1fr_1fr]">
        <KpiCard
          totalsThisMonth={summary.totalsThisMonth}
          totalsLastMonth={summary.totalsLastMonth}
          monthlySeries={summary.monthlySeries}
        />
        <SummaryCard
          reviewQueueCount={summary.reviewQueueCount}
          documentsProcessedToday={summary.documentsProcessedToday}
        />
      </div>

      <div className="grid grid-cols-1 gap-5 shell:grid-cols-2">
        <CategoryBreakdown categories={hasSpendData ? summary.categoryBreakdown : []} />
        <SpendOverTimeChart series={hasSpendData ? summary.monthlySeries : []} />
      </div>

      <RecentExpenses expenses={recentExpenses} categoryName={categoryName} />
    </div>
  );
}
