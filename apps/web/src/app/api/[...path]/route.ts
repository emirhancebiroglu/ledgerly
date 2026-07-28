import { NextRequest } from "next/server";
import { apiFetchAuthenticated } from "@/lib/api-server";

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
  const search = request.nextUrl.search;
  const upstreamPath = `/api/v1/${path.join("/")}${search}`;

  const headers = new Headers();
  const contentType = request.headers.get("content-type");
  if (contentType) {
    headers.set("content-type", contentType);
  }
  const idempotencyKey = request.headers.get("idempotency-key");
  if (idempotencyKey) {
    headers.set("idempotency-key", idempotencyKey);
  }

  const hasBody = request.method !== "GET" && request.method !== "HEAD";

  const upstream = await apiFetchAuthenticated(upstreamPath, {
    method: request.method,
    headers,
    body: hasBody ? await request.arrayBuffer() : undefined,
  });

  const responseHeaders = new Headers();
  const upstreamContentType = upstream.headers.get("content-type");
  if (upstreamContentType) {
    responseHeaders.set("content-type", upstreamContentType);
  }
  const contentDisposition = upstream.headers.get("content-disposition");
  if (contentDisposition) {
    responseHeaders.set("content-disposition", contentDisposition);
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
