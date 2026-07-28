import {
  clearSessionCookies,
  getAccessToken,
  getRefreshToken,
  setSessionCookies,
} from "@/lib/session";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: string,
  ) {
    super(`API request failed with status ${status}`);
  }
}

/** Raw call to `api` with no auth attached — used by login/register, which have none yet. */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${API_BASE_URL}${path}`, init);
}

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = await getRefreshToken();
  if (!refreshToken) {
    return null;
  }

  const response = await apiFetch("/api/v1/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });

  if (!response.ok) {
    await clearSessionCookies();
    return null;
  }

  const tokens = (await response.json()) as AuthTokens;
  await setSessionCookies(tokens);
  return tokens.accessToken;
}

/**
 * Forwards one request to `api` with the session's bearer token attached. On a 401, refreshes
 * exactly once and retries with the new token — a second 401 after that is returned as-is
 * rather than looping.
 */
export async function apiFetchAuthenticated(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const accessToken = await getAccessToken();
  if (!accessToken) {
    return new Response(null, { status: 401 });
  }

  const withAuth = (token: string): RequestInit => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    return { ...init, headers };
  };

  const first = await apiFetch(path, withAuth(accessToken));
  if (first.status !== 401) {
    return first;
  }

  const refreshed = await refreshAccessToken();
  if (!refreshed) {
    return first;
  }

  return apiFetch(path, withAuth(refreshed));
}
