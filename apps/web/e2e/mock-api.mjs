// A minimal stand-in for apps/api used only by Playwright's webServer — real E2E screen tests
// (T4+) will replace individual routes as they're needed, but the shell (T3) just needs enough
// of the auth and dashboard-summary contract to get past proxy.ts's cookie check and render.
import { createServer } from "node:http";

// Matches api-server.ts's fallback (`NEXT_PUBLIC_API_URL ?? "http://localhost:8080"`) so the
// build doesn't need NEXT_PUBLIC_API_URL set at all — one less thing to keep in sync across a
// `next build` step and Playwright's webServer, which don't share a shell environment the same
// way on every OS.
const PORT = Number(process.env.MOCK_API_PORT ?? 8080);

const TOKENS = { accessToken: "e2e-access-token", refreshToken: "e2e-refresh-token" };

const DASHBOARD_SUMMARY = {
  totalsThisMonth: [{ currency: "EUR", amountMinor: 123456 }],
  totalsLastMonth: [{ currency: "EUR", amountMinor: 95690 }],
  categoryBreakdown: [],
  monthlySeries: [],
  reviewQueueCount: 3,
  documentsProcessedToday: 1,
};

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(json) });
  res.end(json);
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);
  const auth = req.headers.authorization;

  if (req.method === "POST" && url.pathname === "/api/v1/auth/login") {
    return send(res, 200, TOKENS);
  }
  if (req.method === "POST" && url.pathname === "/api/v1/auth/refresh") {
    return send(res, 200, TOKENS);
  }
  if (url.pathname === "/api/v1/dashboard/summary") {
    if (auth !== `Bearer ${TOKENS.accessToken}`) {
      return send(res, 401, {});
    }
    return send(res, 200, DASHBOARD_SUMMARY);
  }

  send(res, 404, { message: "not stubbed in e2e mock-api" });
});

server.listen(PORT, () => {
  console.log(`e2e mock-api listening on :${PORT}`);
});
