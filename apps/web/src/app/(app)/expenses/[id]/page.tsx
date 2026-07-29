import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { getExpenseDetail } from "@/lib/expense-detail";
import { DocumentViewer } from "@/components/expense-detail/document-viewer";
import { ExtractedFields } from "@/components/expense-detail/extracted-fields";
import { LedgerEntries } from "@/components/expense-detail/ledger-entries";
import { AgentTimeline } from "@/components/expense-detail/agent-timeline";

interface ExpenseDetailPageProps {
  params: Promise<{ id: string }>;
}

export default async function ExpenseDetailPage({ params }: ExpenseDetailPageProps) {
  const { id } = await params;
  const result = await getExpenseDetail(id);

  if (!result.ok) {
    if (result.status === 404) {
      notFound();
    }
    return (
      <div className="max-w-[1080px] p-6 md:p-8">
        <div className="rounded-xl border border-border bg-card p-8 text-center text-sm text-muted-foreground">
          Couldn&apos;t load this expense. Try refreshing the page.
        </div>
      </div>
    );
  }

  const { expense } = result;

  return (
    <div className="flex max-w-[1080px] flex-col gap-4 p-6 md:p-8">
      <Link
        href="/expenses"
        className="flex w-fit items-center gap-1.5 text-[12.5px] text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-3.5" aria-hidden />
        Back to expenses
      </Link>

      <div className="grid grid-cols-1 gap-5 shell:grid-cols-2">
        <DocumentViewer
          documentId={expense.document.id}
          contentType={expense.document.contentType}
          filename={expense.document.filename}
        />

        <div className="flex flex-col gap-4">
          <ExtractedFields expense={expense} />
          <LedgerEntries entries={expense.ledgerEntries} />
          <AgentTimeline activity={expense.activity} />
        </div>
      </div>
    </div>
  );
}
