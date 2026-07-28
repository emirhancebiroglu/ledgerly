import { BudgetList } from "@/components/budgets/budget-list";
import { listBudgets } from "@/lib/budgets-server";
import { listCategories } from "@/lib/categories";

export default async function BudgetsPage() {
  const [budgetResult, categories] = await Promise.all([listBudgets(), listCategories()]);

  if (!budgetResult.ok) {
    return <div className="max-w-[1080px] p-6 md:p-8"><div className="rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-danger">Couldn&apos;t load budgets. Refresh to try again.</div></div>;
  }

  return (
    <div className="flex max-w-[1080px] flex-col gap-5 p-6 md:p-8">
      <header><p className="text-xs font-semibold tracking-[0.12em] text-primary uppercase">Planning</p><h1 className="mt-1 font-heading text-2xl font-semibold tracking-tight">Budgets</h1><p className="mt-1 text-sm text-muted-foreground">Track exact monthly category spend before it becomes a surprise.</p></header>
      <BudgetList initialBudgets={budgetResult.budgets} categories={categories} />
    </div>
  );
}
