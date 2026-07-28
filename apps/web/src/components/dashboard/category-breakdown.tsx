import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import type { CategoryBreakdownEntry } from "@/lib/dashboard";

interface CategoryBreakdownProps {
  categories: CategoryBreakdownEntry[];
  /** Display currency for the amounts — see the note on `dashboard/page.tsx` about
   * `categoryBreakdown` summing across currencies (api's own documented gap, not this
   * component's to fix). */
  currency: string;
}

export function CategoryBreakdown({ categories, currency }: CategoryBreakdownProps) {
  if (categories.length === 0) {
    return (
      <Card data-testid="category-breakdown" className="p-[22px_24px]">
        <div className="mb-4 text-[12.5px] font-medium text-muted-foreground">
          Spend by category
        </div>
        <div className="text-sm text-muted-foreground">No categorized spend yet.</div>
      </Card>
    );
  }

  const max = Math.max(...categories.map((c) => c.amountMinor), 1);

  return (
    <Card data-testid="category-breakdown" className="p-[22px_24px]">
      <div className="mb-4 text-[12.5px] font-medium text-muted-foreground">
        Spend by category
      </div>
      <div className="flex flex-col gap-3">
        {categories.map((category) => {
          const pct = Math.round((category.amountMinor / max) * 100);
          return (
            <div key={category.categoryId}>
              <div className="mb-1.5 flex justify-between text-[12.5px]">
                <span className="font-medium">{category.categoryName}</span>
                <span className="font-mono text-muted-foreground">
                  {formatMoney(category.amountMinor, currency)}
                </span>
              </div>
              <div
                role="progressbar"
                aria-label={`${category.categoryName}: ${pct}% of the highest category`}
                aria-valuenow={pct}
                aria-valuemin={0}
                aria-valuemax={100}
                className="h-1.5 overflow-hidden rounded-full bg-muted"
              >
                <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
