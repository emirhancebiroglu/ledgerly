import { Card } from "@/components/ui/card";
import { formatMoney } from "@/lib/money";
import { groupByCurrency, type CategoryBreakdownEntry } from "@/lib/dashboard";

interface CategoryBreakdownProps {
  categories: CategoryBreakdownEntry[];
}

export function CategoryBreakdown({ categories }: CategoryBreakdownProps) {
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

  // Percentages within one currency section always sum to 100% -- each section's own total is
  // the denominator, never the cross-currency total, which would make the bars misleading
  // relative to a number never shown on screen.
  const sections = groupByCurrency(categories);
  const multipleCurrencies = sections.length > 1;

  return (
    <Card data-testid="category-breakdown" className="p-[22px_24px]">
      <div className="mb-4 text-[12.5px] font-medium text-muted-foreground">
        Spend by category
      </div>
      <div className="flex flex-col gap-5">
        {sections.map(([currency, categoriesInCurrency]) => {
          const total = categoriesInCurrency.reduce((sum, category) => sum + category.amountMinor, 0);
          return (
            <div key={currency}>
              {multipleCurrencies && (
                <div className="mb-1.5 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
                  {currency}
                </div>
              )}
              <div className="flex flex-col gap-3">
                {categoriesInCurrency.map((category) => {
                  const pct = total === 0 ? 0 : Math.round((category.amountMinor / total) * 100);
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
                        aria-label={`${category.categoryName}: ${pct}% of total spend`}
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
            </div>
          );
        })}
      </div>
    </Card>
  );
}
