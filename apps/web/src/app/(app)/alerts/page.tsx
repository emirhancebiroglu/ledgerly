import { AlertList } from "@/components/alerts/alert-list";
import { listAlerts } from "@/lib/alerts-server";
import { listCategories } from "@/lib/categories";

export default async function AlertsPage() {
  const [alertResult, categories] = await Promise.all([listAlerts(), listCategories()]);

  if (!alertResult.ok) {
    return (
      <div className="max-w-[820px] p-6 md:p-8">
        <div className="rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-danger">
          Couldn&apos;t load alerts. Refresh to try again.
        </div>
      </div>
    );
  }

  return (
    <div className="flex max-w-[820px] flex-col gap-5 p-6 md:p-8">
      <header>
        <p className="text-xs font-semibold tracking-[0.12em] text-primary uppercase">Alerts</p>
        <h1 className="mt-1 font-heading text-2xl font-semibold tracking-tight">Alerts</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Budget crossings, unusual spend, and low-confidence categorizations that need a look.
        </p>
      </header>
      <AlertList initialAlerts={alertResult.alerts} categories={categories} />
    </div>
  );
}
