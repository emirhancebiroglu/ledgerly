import { listExpenses } from "@/lib/expenses";
import { listCategories, categoryNameLookup } from "@/lib/categories";
import { ExpensesFilters } from "@/components/expenses/expenses-filters";
import { ExpenseList } from "@/components/expenses/expense-list";

interface ExpensesPageProps {
  searchParams: Promise<{ status?: string; search?: string; sort?: string }>;
}

export default async function ExpensesPage({ searchParams }: ExpensesPageProps) {
  const { status, search, sort } = await searchParams;

  const [result, categories] = await Promise.all([
    listExpenses({ status, search, sort }),
    listCategories(),
  ]);
  const categoryName = categoryNameLookup(categories);

  return (
    <div className="flex max-w-[1080px] flex-col gap-4 p-6 md:p-8">
      <ExpensesFilters />
      <ExpenseList
        expenses={result.ok ? result.expenses : []}
        categoryName={categoryName}
        errorMessage={result.ok ? undefined : result.message}
      />
    </div>
  );
}
