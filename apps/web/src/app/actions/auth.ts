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

  const next = String(formData.get("next") ?? "");
  const isSafeRelativePath = next.startsWith("/") && !next.startsWith("//");
  redirect(isSafeRelativePath ? next : "/dashboard");
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
