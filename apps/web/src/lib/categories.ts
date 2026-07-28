import { apiFetchAuthenticated } from "@/lib/api-server";

export interface Category {
  id: string;
  name: string;
}

export async function listCategories(): Promise<Category[]> {
  const response = await apiFetchAuthenticated("/api/v1/categories");
  if (!response.ok) {
    return [];
  }
  return (await response.json()) as Category[];
}

/** `ExpenseResponse` carries only `categoryId` (no name) — this resolves it against
 * `GET /api/v1/categories`, called once per page render and shared across rows. */
export function categoryNameLookup(categories: Category[]): (categoryId: string | null) => string {
  const byId = new Map(categories.map((c) => [c.id, c.name]));
  return (categoryId) => (categoryId ? (byId.get(categoryId) ?? "Uncategorized") : "Uncategorized");
}
