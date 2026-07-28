import { NextRequest } from "next/server";
import { apiFetchAuthenticated } from "@/lib/api-server";

/** Headers forwarded verbatim from the browser request to `api`. */
const FORWARDED_REQUEST_HEADERS = ["content-type", "idempotency-key"];

/** Matches `spring.servlet.multipart.max-request-size` (application.yml) — rejected here rather
 * than after buffering the whole body into Node memory for nothing. */
const MAX_REQUEST_BODY_BYTES = 12 * 1024 * 1024;

/** Headers forwarded verbatim from `api`'s response back to the browser — includes the
 * `text/event-stream` cache-control pair M7a T6's SSE endpoint needs to reach the browser
 * unbuffered by any intermediary. */
const FORWARDED_RESPONSE_HEADERS = [
  "content-type",
  "content-disposition",
  "cache-control",
  "connection",
];

/**
 * Rejects any path segment that isn't a plain identifier, so `path.join("/")` can never resolve
 * outside `/api/v1/` (e.g. a `..` segment escaping to `/actuator/**`, which `SecurityConfig`
 * permits unauthenticated). Every real route under `/api/v1/**` is UUIDs, enum-like status
 * strings, or fixed path components — none of those need `.`, `/`, or a leading `%`.
 */
function isSafePathSegment(segment: string): boolean {
  return segment.length > 0 && !segment.includes(".") && !segment.includes("%") && segment !== "..";
}

/**
 * Same-origin proxy to `api`'s `/api/v1/*` — the browser only ever talks to this route, never to
 * `api` directly. Two reasons: `api` has no CORS policy for `/api/v1/**` (only `management`'s
 * actuator endpoints allow cross-origin `GET`), and this is the only place a bearer token, which
 * lives in an httpOnly cookie the browser cannot read, can be attached to the request.
 */
async function handler(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await context.params;
  if (path.length === 0 || !path.every(isSafePathSegment)) {
    return new Response(null, { status: 404 });
  }

  const isStateChanging = request.method !== "GET" && request.method !== "HEAD";
  if (isStateChanging) {
    // `sameSite: "lax"` on the session cookie already blocks a cross-site form POST from
    // carrying it, but that's Chrome/Edge/Firefox behavior, not a guarantee every client
    // honors — an explicit same-origin check is the actual CSRF defense.
    const origin = request.headers.get("origin");
    if (origin && origin !== request.nextUrl.origin) {
      return new Response(null, { status: 403 });
    }
  }

  const search = request.nextUrl.search;
  const upstreamPath = `/api/v1/${path.join("/")}${search}`;

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) {
      headers.set(name, value);
    }
  }

  let body: ArrayBuffer | undefined;
  if (isStateChanging) {
    body = await request.arrayBuffer();
    if (body.byteLength > MAX_REQUEST_BODY_BYTES) {
      return new Response(null, { status: 413 });
    }
  }

  // Buffered rather than streamed: a request that hits a 401 (expired access token) is retried
  // once with a fresh token, and a ReadableStream body can only be read once — a streamed
  // upload would go out empty on that retry. The 12MB cap above bounds how large that buffer
  // ever gets.
  const upstream = await apiFetchAuthenticated(upstreamPath, {
    method: request.method,
    headers,
    body,
  });

  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) {
      responseHeaders.set(name, value);
    }
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}

export {
  handler as GET,
  handler as POST,
  handler as PUT,
  handler as PATCH,
  handler as DELETE,
};
