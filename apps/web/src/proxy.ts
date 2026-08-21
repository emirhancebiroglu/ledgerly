import { NextRequest, NextResponse } from "next/server";
import { ACCESS_TOKEN_COOKIE } from "@/lib/session";

const LOGIN_PATH = "/login";
const DASHBOARD_PATH = "/dashboard";
const ROOT_PATH = "/";

/** Screens that exist to get a visitor a session; reaching one while signed in is pointless. */
const PUBLIC_PATHS = [LOGIN_PATH, "/register"];

/**
 * Optimistic auth check — cookie presence only, no token verification (that happens per-request
 * in the `/api/[...path]` proxy and in every `api` endpoint). Good enough to avoid rendering a
 * protected page that will immediately 401 underneath, not a substitute for real authorization.
 *
 * Everything not listed in `PUBLIC_PATHS` is treated as protected, so a newly added route is
 * guarded by default rather than by remembering to list it. `/` is an entry point rather than a
 * screen: it resolves to the dashboard or to login and never renders content of its own.
 */
export function proxy(request: NextRequest): NextResponse {
  const { pathname, search } = request.nextUrl;
  const hasSession = request.cookies.has(ACCESS_TOKEN_COOKIE);

  if (pathname === ROOT_PATH) {
    return NextResponse.redirect(new URL(hasSession ? DASHBOARD_PATH : LOGIN_PATH, request.url));
  }

  if (PUBLIC_PATHS.includes(pathname)) {
    return hasSession
      ? NextResponse.redirect(new URL(DASHBOARD_PATH, request.url))
      : NextResponse.next();
  }

  if (!hasSession) {
    const loginUrl = new URL(LOGIN_PATH, request.url);
    // Carry the full destination, query string included, so a filtered list or a deep link
    // survives the round trip through sign-in.
    loginUrl.searchParams.set("next", `${pathname}${search}`);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
