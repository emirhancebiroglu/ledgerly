import { cookies } from "next/headers";

const ACCESS_TOKEN_COOKIE = "ledgerly_access_token";
const REFRESH_TOKEN_COOKIE = "ledgerly_refresh_token";

/** Matches `ledgerly.auth.access-token-ttl-minutes` default (apps/api/src/main/resources/application.yml). */
const ACCESS_TOKEN_MAX_AGE_SECONDS = 15 * 60;
/** Matches `ledgerly.auth.refresh-token-ttl-days` default. */
const REFRESH_TOKEN_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

const COOKIE_BASE = {
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax" as const,
  path: "/",
};

export interface Tokens {
  accessToken: string;
  refreshToken: string;
}

export async function setSessionCookies(tokens: Tokens, persistent = true): Promise<void> {
  const store = await cookies();
  store.set(ACCESS_TOKEN_COOKIE, tokens.accessToken, {
    ...COOKIE_BASE,
    maxAge: ACCESS_TOKEN_MAX_AGE_SECONDS,
  });
  store.set(
    REFRESH_TOKEN_COOKIE,
    tokens.refreshToken,
    persistent
      ? { ...COOKIE_BASE, maxAge: REFRESH_TOKEN_MAX_AGE_SECONDS }
      : COOKIE_BASE,
  );
}

export async function clearSessionCookies(): Promise<void> {
  const store = await cookies();
  store.delete(ACCESS_TOKEN_COOKIE);
  store.delete(REFRESH_TOKEN_COOKIE);
}

export async function getAccessToken(): Promise<string | undefined> {
  const store = await cookies();
  return store.get(ACCESS_TOKEN_COOKIE)?.value;
}

export async function getRefreshToken(): Promise<string | undefined> {
  const store = await cookies();
  return store.get(REFRESH_TOKEN_COOKIE)?.value;
}

export { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE };
