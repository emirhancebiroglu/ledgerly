import { listReviewQueue } from "@/lib/review";
import { listCategories } from "@/lib/categories";
import { ReviewQueueTable } from "@/components/review/review-queue-table";

export default async function ReviewPage() {
  const [expenses, categories] = await Promise.all([listReviewQueue(), listCategories()]);

  return (
    <div className="max-w-[1080px] p-6 md:p-8">
      <ReviewQueueTable initialExpenses={expenses} categories={categories} />
    </div>
  );
}
