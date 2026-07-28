// A minimal stand-in for apps/api used only by Playwright's webServer — enough of the real
// contracts (auth, dashboard summary, expenses list, categories) for the shell and screens built
// so far to render against real-shaped data, not the full Spring + Postgres + Redis stack.
import { createServer } from "node:http";

// Matches api-server.ts's fallback (`NEXT_PUBLIC_API_URL ?? "http://localhost:8080"`) so the
// build doesn't need NEXT_PUBLIC_API_URL set at all — one less thing to keep in sync across a
// `next build` step and Playwright's webServer, which don't share a shell environment the same
// way on every OS.
const PORT = Number(process.env.MOCK_API_PORT ?? 8080);

const TOKENS = { accessToken: "e2e-access-token", refreshToken: "e2e-refresh-token" };

const CATEGORIES = [
  { id: "cat-1", name: "Software" },
  { id: "cat-2", name: "Travel" },
  { id: "cat-3", name: "Office supplies" },
];

const DASHBOARD_SUMMARY = {
  totalsThisMonth: [{ currency: "EUR", amountMinor: 8421300 }],
  totalsLastMonth: [{ currency: "EUR", amountMinor: 9569000 }],
  categoryBreakdown: [
    { categoryId: "cat-1", categoryName: "Software", amountMinor: 4200000 },
    { categoryId: "cat-2", categoryName: "Travel", amountMinor: 2800000 },
    { categoryId: "cat-3", categoryName: "Office supplies", amountMinor: 1421300 },
  ],
  monthlySeries: [
    { month: "2026-02", amountMinor: 6000000 },
    { month: "2026-03", amountMinor: 7200000 },
    { month: "2026-04", amountMinor: 5100000 },
    { month: "2026-05", amountMinor: 8900000 },
    { month: "2026-06", amountMinor: 9569000 },
    { month: "2026-07", amountMinor: 8421300 },
  ],
  reviewQueueCount: 3,
  documentsProcessedToday: 17,
};

const EXPENSES = [
  {
    id: "exp-1",
    documentId: "doc-1",
    vendor: "Northwind Logistics",
    categoryId: "cat-2",
    ledgerTransactionId: "txn-1",
    amountMinor: 234000,
    currency: "EUR",
    categorizationConfidence: 0.94,
    citation: null,
    status: "POSTED",
    createdAt: "2026-07-24T10:00:00Z",
  },
  {
    id: "exp-2",
    documentId: "doc-2",
    vendor: "CloudBase Inc.",
    categoryId: "cat-1",
    ledgerTransactionId: "txn-2",
    amountMinor: 89900,
    currency: "EUR",
    categorizationConfidence: 0.98,
    citation: null,
    status: "POSTED",
    createdAt: "2026-07-23T09:00:00Z",
  },
  {
    id: "exp-3",
    documentId: "doc-3",
    vendor: "Office Depot",
    categoryId: "cat-3",
    ledgerTransactionId: null,
    amountMinor: 15600,
    currency: "EUR",
    categorizationConfidence: 0.62,
    citation: null,
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-22T14:00:00Z",
  },
  {
    id: "exp-4",
    documentId: "doc-4",
    vendor: "Skyline Air",
    categoryId: "cat-2",
    ledgerTransactionId: "txn-4",
    amountMinor: 542300,
    currency: "EUR",
    categorizationConfidence: 0.91,
    citation: null,
    status: "POSTED",
    createdAt: "2026-07-21T08:00:00Z",
  },
  {
    id: "exp-5",
    documentId: "doc-5",
    vendor: "Figma",
    categoryId: "cat-1",
    ledgerTransactionId: "txn-5",
    amountMinor: 4500,
    currency: "EUR",
    categorizationConfidence: 0.99,
    citation: null,
    status: "POSTED",
    createdAt: "2026-07-20T08:00:00Z",
  },
];

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(json) });
  res.end(json);
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);
  const auth = req.headers.authorization;
  const isAuthed = auth === `Bearer ${TOKENS.accessToken}`;

  if (req.method === "POST" && url.pathname === "/api/v1/auth/login") {
    return send(res, 200, TOKENS);
  }
  if (req.method === "POST" && url.pathname === "/api/v1/auth/refresh") {
    return send(res, 200, TOKENS);
  }
  if (url.pathname === "/api/v1/dashboard/summary") {
    if (!isAuthed) return send(res, 401, {});
    return send(res, 200, DASHBOARD_SUMMARY);
  }
  if (url.pathname === "/api/v1/expenses") {
    if (!isAuthed) return send(res, 401, {});

    const status = url.searchParams.get("status");
    if (status && !["POSTED", "NEEDS_REVIEW"].includes(status)) {
      return send(res, 400, { detail: `Unknown status: ${status}` });
    }
    // Mirrors ExpenseListQuery.parse's split(",", 2) exactly: "date," has a present-but-empty
    // second part (parts.length === 2), which the real API 400s on just like "date,bogus" —
    // only a genuinely absent sort param (parts.length === 1) defaults the direction to desc.
    const sort = url.searchParams.get("sort");
    let sortField = "date";
    let sortDir = "desc";
    if (sort) {
      const parts = sort.split(",");
      sortField = parts[0];
      if (!["date", "amount"].includes(sortField)) {
        return send(res, 400, { detail: `Unknown sort field: ${sortField}` });
      }
      if (parts.length >= 2) {
        sortDir = parts[1];
        if (!["asc", "desc"].includes(sortDir)) {
          return send(res, 400, { detail: `Unknown sort direction: ${sortDir}` });
        }
      }
    }

    const search = url.searchParams.get("search")?.toLowerCase();
    let results = EXPENSES.filter((e) => {
      if (status && e.status !== status) return false;
      if (search && !e.vendor.toLowerCase().includes(search)) return false;
      return true;
    });

    results = [...results].sort((a, b) => {
      const key = sortField === "amount" ? "amountMinor" : "createdAt";
      const cmp = a[key] < b[key] ? -1 : a[key] > b[key] ? 1 : 0;
      return sortDir === "asc" ? cmp : -cmp;
    });

    // Matches ExpenseController's @RequestParam(defaultValue = "20") — a caller that omits
    // `size` should see the real API's actual default, not "everything," or an e2e test could
    // pass here while silently relying on a page size the real api would never give it.
    const size = Number(url.searchParams.get("size") ?? 20);
    return send(res, 200, results.slice(0, size));
  }
  if (url.pathname === "/api/v1/categories") {
    if (!isAuthed) return send(res, 401, {});
    return send(res, 200, CATEGORIES);
  }

  send(res, 404, { message: "not stubbed in e2e mock-api" });
});

server.listen(PORT, () => {
  console.log(`e2e mock-api listening on :${PORT}`);
});
