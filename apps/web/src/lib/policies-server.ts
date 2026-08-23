import { apiFetchAuthenticated } from "@/lib/api-server";
import {
  parsePolicyChunks,
  parsePolicyDocument,
  parsePolicyDocuments,
  type PolicyChunk,
  type PolicyDocument,
} from "@/lib/policies";

export type PolicyListResult =
  | { ok: true; documents: PolicyDocument[] }
  | { ok: false };

export type PolicyDocumentResult =
  | { ok: true; document: PolicyDocument }
  | { ok: false; status: number };

export type PolicyChunksResult =
  | { ok: true; chunks: PolicyChunk[] }
  | { ok: false; status: number };

export async function listPolicyDocuments(): Promise<PolicyListResult> {
  const response = await apiFetchAuthenticated("/api/v1/policies?size=100");
  if (!response.ok) {
    return { ok: false };
  }
  const payload = await response.json();
  const parsed = parsePolicyDocuments(payload);
  if ("error" in parsed) {
    return { ok: false };
  }
  return { ok: true, documents: parsed };
}

export async function getPolicyDocument(id: string): Promise<PolicyDocumentResult> {
  const response = await apiFetchAuthenticated(`/api/v1/policies/${id}`);
  if (!response.ok) {
    return { ok: false, status: response.status };
  }
  const payload = await response.json();
  const parsed = parsePolicyDocument(payload);
  if ("error" in parsed) {
    return { ok: false, status: 502 };
  }
  return { ok: true, document: parsed };
}

/** Fetches every chunk page and concatenates — the detail screen renders the full passage list
 * client-side (search/fold live in the browser), so there is no server-side pagination UI to
 * drive a partial fetch. `size=200` matches the API's chunk-endpoint maximum page size. */
export async function listPolicyChunks(id: string): Promise<PolicyChunksResult> {
  const chunks: PolicyChunk[] = [];
  let page = 0;
  for (;;) {
    const response = await apiFetchAuthenticated(
      `/api/v1/policies/${id}/chunks?page=${page}&size=200`,
    );
    if (!response.ok) {
      return { ok: false, status: response.status };
    }
    const payload = await response.json();
    const parsed = parsePolicyChunks(payload);
    if ("error" in parsed) {
      return { ok: false, status: 502 };
    }
    chunks.push(...parsed);
    if (parsed.length < 200) {
      break;
    }
    page += 1;
  }
  return { ok: true, chunks };
}
