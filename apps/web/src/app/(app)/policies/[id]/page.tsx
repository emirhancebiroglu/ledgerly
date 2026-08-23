import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { PolicyDetail } from "@/components/policies/policy-detail";
import { getPolicyDocument, listPolicyChunks } from "@/lib/policies-server";

interface PolicyDetailPageProps {
  params: Promise<{ id: string }>;
}

export default async function PolicyDetailPage({ params }: PolicyDetailPageProps) {
  const { id } = await params;
  const documentResult = await getPolicyDocument(id);

  if (!documentResult.ok) {
    if (documentResult.status === 404) {
      notFound();
    }
    return (
      <div className="max-w-[1080px] p-6 md:p-8">
        <div className="rounded-xl border border-border bg-card p-8 text-center text-sm text-muted-foreground">
          Couldn&apos;t load this policy document. Try refreshing the page.
        </div>
      </div>
    );
  }

  const { document } = documentResult;
  const chunksResult = document.status === "EMBEDDED" ? await listPolicyChunks(id) : { ok: true as const, chunks: [] };

  return (
    <div className="flex max-w-[1080px] flex-col gap-4 p-6 md:p-8">
      <Link
        href="/policies"
        className="flex w-fit items-center gap-1.5 text-[12.5px] text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-3.5" aria-hidden />
        All policy documents
      </Link>
      <PolicyDetail document={document} chunks={chunksResult.ok ? chunksResult.chunks : []} />
    </div>
  );
}
