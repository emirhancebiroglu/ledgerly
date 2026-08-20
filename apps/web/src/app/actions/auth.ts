"use server";

import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api-server";
import { clearSessionCookies, setSessionCookies } from "@/lib/session";

export interface AuthFormState {
  error?: string;
}

async function parseAuthResponse(
  response: Response,
  persistentSession = true,
): Promise<AuthFormState> {
  if (!response.ok) {
    const body = await response.text();
    let message = "Something went wrong. Please try again.";
    try {
      const parsed = JSON.parse(body) as { message?: string };
      message = parsed.message ?? message;
    } catch {
      // Non-JSON error body (e.g. plain 500 text) — keep the generic message.
    }
    if (response.status === 401) {
      message = "Incorrect email or password.";
    }
    return { error: message };
  }

  const tokens = (await response.json()) as { accessToken: string; refreshToken: string };
  await setSessionCookies(tokens, persistentSession);
  return {};
}

export async function login(
  _prevState: AuthFormState | undefined,
  formData: FormData,
): Promise<AuthFormState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const remember = formData.get("remember") === "on";

  const response = await apiFetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });

  const result = await parseAuthResponse(response, remember);
  if (result.error) {
    return result;
  }

  redirect(safeRedirectTarget(String(formData.get("next") ?? "")));
}

/**
 * `next` arrives from a query parameter an attacker can hand a victim, so it decides where a
 * freshly authenticated session lands. Resolve it and require the result to stay on this origin
 * rather than testing string prefixes: `/\evil.com` starts with a single `/` yet resolves
 * off-origin, because a backslash is equivalent to a slash in a special scheme. Anything that
 * leaves the origin — or fails to parse at all — falls back to the dashboard.
 */
function safeRedirectTarget(next: string): string {
  const FALLBACK = "/dashboard";
  if (!next) {
    return FALLBACK;
  }

  // A fixed opaque base: only paths that stay on it are in-app destinations.
  const base = "http://ledgerly.invalid";
  let resolved: URL;
  try {
    resolved = new URL(next, base);
  } catch {
    return FALLBACK;
  }

  if (resolved.origin !== base) {
    return FALLBACK;
  }

  // Return the resolved path so a traversal or encoded form cannot smuggle anything past the
  // origin check that a raw echo of `next` would preserve.
  return `${resolved.pathname}${resolved.search}${resolved.hash}`;
}

export async function register(
  _prevState: AuthFormState | undefined,
  formData: FormData,
): Promise<AuthFormState> {
  const fullName = String(formData.get("fullName") ?? "");
  const company = String(formData.get("company") ?? "");
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  const response = await apiFetch("/api/v1/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, company, email, password }),
  });

  const result = await parseAuthResponse(response);
  if (result.error) {
    return result;
  }

  redirect("/dashboard");
}

export async function logout(): Promise<void> {
  await clearSessionCookies();
  redirect("/login");
}
