import { listExpenses } from "@/lib/expenses";
import { listCategories, categoryNameLookup } from "@/lib/categories";
import { ExpensesFilters } from "@/components/expenses/expenses-filters";
import { ExpenseList } from "@/components/expenses/expense-list";

interface ExpensesPageProps {
  searchParams: Promise<{ status?: string; search?: string; sort?: string }>;
}

// ExpenseService.resolveSize caps every request at 100 server-side regardless of what's asked
// for — requesting that cap explicitly (rather than omitting `size` and getting the API's
// unrelated default of 20) is the largest single page this endpoint will ever return; there is
// no pagination UI yet, so a result landing exactly on this cap is the signal that more exist.
const MAX_PAGE_SIZE = 100;

export default async function ExpensesPage({ searchParams }: ExpensesPageProps) {
  const { status, search, sort } = await searchParams;

  const [result, categories] = await Promise.all([
    listExpenses({ status, search, sort, size: MAX_PAGE_SIZE }),
    listCategories(),
  ]);
  const categoryName = categoryNameLookup(categories);
  const expenses = result.ok ? result.expenses : [];
  const mayHaveMore = expenses.length === MAX_PAGE_SIZE;

  return (
    <div className="flex max-w-[1080px] flex-col gap-4 p-6 md:p-8">
      <ExpensesFilters />
      <ExpenseList
        expenses={expenses}
        categoryName={categoryName}
        errorMessage={result.ok ? undefined : result.message}
      />
      {mayHaveMore && (
        <p className="text-center text-xs text-muted-foreground">
          Showing the first {MAX_PAGE_SIZE} results. Narrow your search or filters to see more.
        </p>
      )}
    </div>
  );
}
