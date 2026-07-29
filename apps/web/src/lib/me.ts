import { apiFetchAuthenticated } from "@/lib/api-server";

export interface CurrentUser {
  userId: string;
  fullName: string;
  email: string;
  organizationId: string;
  organizationName: string;
  baseCurrency: string;
}

export async function getCurrentUser(): Promise<CurrentUser | null> {
  const response = await apiFetchAuthenticated("/api/v1/me");
  return response.ok ? ((await response.json()) as CurrentUser) : null;
}
