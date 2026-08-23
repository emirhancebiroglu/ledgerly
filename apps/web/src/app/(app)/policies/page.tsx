import { PolicyList } from "@/components/policies/policy-list";
import { listPolicyDocuments } from "@/lib/policies-server";

// PolicyQueryService.MAX_DOCUMENT_PAGE_SIZE caps every request at 100 server-side — requesting
// that cap explicitly is the largest single page this endpoint will ever return; there is no
// pagination UI yet, so a result landing exactly on this cap is the signal that more exist.
const MAX_PAGE_SIZE = 100;

export default async function PoliciesPage() {
  const result = await listPolicyDocuments();
  const documents = result.ok ? result.documents : [];
  const mayHaveMore = documents.length === MAX_PAGE_SIZE;

  return (
    <div className="flex max-w-[1080px] flex-col gap-5 p-6 md:p-8">
      <header>
        <p className="text-xs font-semibold tracking-[0.12em] text-primary uppercase">Retrieval</p>
        <h1 className="mt-1 font-heading text-2xl font-semibold tracking-tight">Policies</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Policy PDFs indexed for retrieval. When Ledgerly categorizes an expense it reads the
          nearest passages from these documents and may quote one as its justification.
        </p>
      </header>
      <PolicyList initialDocuments={documents} loadError={!result.ok} />
      {mayHaveMore && (
        <p className="text-center text-xs text-muted-foreground">
          Showing the first {MAX_PAGE_SIZE} documents.
        </p>
      )}
    </div>
  );
}
