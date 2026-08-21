import { redirect } from "next/navigation";
import { getAccessToken } from "@/lib/session";

/**
 * `/` is an entry point, not a screen. `proxy.ts` already resolves it before render; this is the
 * same decision made a second time at the page level so the root can never fall back to rendering
 * content of its own if the proxy is bypassed or its matcher changes.
 *
 * Like the proxy, this is an optimistic cookie check — the API authorizes every actual request.
 */
export default async function Home() {
  redirect((await getAccessToken()) ? "/dashboard" : "/login");
}
