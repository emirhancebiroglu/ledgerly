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

// Smallest-possible valid 1x1 PNG and PDF, real bytes so DocumentViewer's blob: URL actually
// decodes into something the browser can render, not just a content-type label.
const TINY_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);
const TINY_PDF = Buffer.from(
  "%PDF-1.1\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\ntrailer<</Root 1 0 R>>",
  "utf-8",
);

const DOCUMENTS = {
  "doc-1": {
    id: "doc-1",
    filename: "northwind-invoice.pdf",
    contentType: "application/pdf",
    sizeBytes: TINY_PDF.byteLength,
    status: "EXTRACTED",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-24T09:58:00Z",
    bytes: TINY_PDF,
  },
  "doc-2": {
    id: "doc-2",
    filename: "cloudbase-receipt.png",
    contentType: "image/png",
    sizeBytes: TINY_PNG.byteLength,
    status: "EXTRACTED",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-23T08:58:00Z",
    bytes: TINY_PNG,
  },
  "doc-3": {
    id: "doc-3",
    filename: "office-depot-receipt.png",
    contentType: "image/png",
    sizeBytes: TINY_PNG.byteLength,
    status: "NEEDS_REVIEW",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-22T13:58:00Z",
    bytes: TINY_PNG,
  },
  "doc-4": {
    id: "doc-4",
    filename: "skyline-air-ticket.txt",
    contentType: "text/plain",
    sizeBytes: 11,
    status: "EXTRACTED",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-21T07:58:00Z",
    bytes: Buffer.from("plain text", "utf-8"),
  },
  "doc-5": {
    id: "doc-5",
    filename: "figma-invoice.pdf",
    contentType: "application/pdf",
    sizeBytes: TINY_PDF.byteLength,
    status: "EXTRACTED",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-20T07:58:00Z",
    bytes: TINY_PDF,
  },
};

const ACCOUNTS = {
  "cat-1": { id: "acct-expense-software", name: "Software Expense" },
  "cat-2": { id: "acct-expense-travel", name: "Travel Expense" },
  "cat-3": { id: "acct-expense-office", name: "Office Supplies Expense" },
};

function ledgerEntriesFor(expense) {
  if (!expense.ledgerTransactionId) {
    return [];
  }
  const account = ACCOUNTS[expense.categoryId] ?? { id: "acct-unknown", name: "Unknown" };
  return [
    {
      accountId: account.id,
      accountName: account.name,
      direction: "DEBIT",
      amountMinor: expense.amountMinor,
      currency: expense.currency,
    },
    {
      accountId: "acct-cash",
      accountName: "Cash / Bank",
      direction: "CREDIT",
      amountMinor: expense.amountMinor,
      currency: expense.currency,
    },
  ];
}

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(json) });
  res.end(json);
}

// Mirrors DetectedContentType.detect — bytes decide the type, never the filename or declared
// Content-Type, so a test can prove a mislabeled/malicious upload is rejected the same way the
// real api rejects it.
const MAGIC_BYTES = [
  { contentType: "application/pdf", bytes: Buffer.from([0x25, 0x50, 0x44, 0x46]) },
  { contentType: "image/jpeg", bytes: Buffer.from([0xff, 0xd8, 0xff]) },
  {
    contentType: "image/png",
    bytes: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  },
];

function detectContentType(bytes) {
  return MAGIC_BYTES.find((candidate) => bytes.subarray(0, candidate.bytes.length).equals(candidate.bytes))
    ?.contentType;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

/** Minimal multipart/form-data parser — enough to pull the "file" part's filename and bytes out
 * of a real browser-constructed FormData body, not a general-purpose implementation. */
function parseMultipartFile(body, contentType) {
  const boundaryMatch = contentType.match(/boundary=(?:"([^"]+)"|([^;]+))/);
  const boundary = boundaryMatch ? boundaryMatch[1] ?? boundaryMatch[2] : null;
  if (!boundary) return null;

  const delimiter = Buffer.from(`--${boundary}`);
  const parts = [];
  let start = body.indexOf(delimiter);
  while (start !== -1) {
    const next = body.indexOf(delimiter, start + delimiter.length);
    if (next === -1) break;
    parts.push(body.subarray(start + delimiter.length, next));
    start = next;
  }

  for (const part of parts) {
    const headerEnd = part.indexOf("\r\n\r\n");
    if (headerEnd === -1) continue;
    const headerText = part.subarray(0, headerEnd).toString("utf-8");
    if (!headerText.includes('name="file"')) continue;
    const filenameMatch = headerText.match(/filename="([^"]*)"/);
    const fileBytes = part.subarray(headerEnd + 4, part.length - 2); // trim trailing \r\n
    return { filename: filenameMatch?.[1] ?? "upload", bytes: fileBytes };
  }
  return null;
}

// Subscribers per document id, for the SSE endpoint below.
const eventSubscribers = new Map();

function publishStatus(documentId, status, detail) {
  const doc = DOCUMENTS[documentId];
  if (doc) {
    doc.status = status;
    doc.failureReason = detail ?? null;
  }
  const payload = { documentId, organizationId: "org-1", status, detail: detail ?? null };
  for (const res of eventSubscribers.get(documentId) ?? []) {
    res.write(`event: status\ndata: ${JSON.stringify(payload)}\n\n`);
  }
  if (["EXTRACTED", "NEEDS_REVIEW", "FAILED"].includes(status)) {
    for (const res of eventSubscribers.get(documentId) ?? []) {
      res.end();
    }
    eventSubscribers.delete(documentId);
  }
}

function handleLogin(req, res) {
  send(res, 200, TOKENS);
}

function handleRefresh(req, res) {
  send(res, 200, TOKENS);
}

function handleUpload(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  const idempotencyKey = req.headers["idempotency-key"];
  if (!idempotencyKey) {
    return send(res, 400, { detail: "Idempotency-Key header is required" });
  }
  const contentType = req.headers["content-type"] ?? "";
  readBody(req)
    .then((body) => {
      const parsed = parseMultipartFile(body, contentType);
      if (!parsed || parsed.bytes.length === 0) {
        return send(res, 415, { detail: "Uploaded document is empty" });
      }
      const detected = detectContentType(parsed.bytes);
      if (!detected) {
        return send(res, 415, { detail: "Unsupported document type; expected PDF, JPEG or PNG" });
      }
      const id = `doc-upload-${Date.now()}`;
      DOCUMENTS[id] = {
        id,
        filename: parsed.filename,
        contentType: detected,
        sizeBytes: parsed.bytes.length,
        status: "PENDING",
        proposal: null,
        failureReason: null,
        createdAt: new Date().toISOString(),
        bytes: parsed.bytes,
      };
      send(res, 201, {
        id,
        filename: parsed.filename,
        contentType: detected,
        sizeBytes: parsed.bytes.length,
        status: "PENDING",
        failureReason: null,
      });
      // Simulates the real pipeline's PENDING -> PROCESSING -> terminal progression so the
      // upload screen's SSE-driven step list has real transitions to react to, not an
      // instantaneous jump straight to done.
      setTimeout(() => publishStatus(id, "PROCESSING", null), 300);
      setTimeout(() => {
        const outcome = parsed.filename.includes("fail") ? "FAILED" : "EXTRACTED";
        publishStatus(id, outcome, outcome === "FAILED" ? "Unreadable scan" : null);
      }, 900);
    })
    .catch(() => send(res, 500, { detail: "Upload failed" }));
}

function handleDocumentEvents(req, res, isAuthed, documentId) {
  if (!isAuthed) return send(res, 401, {});
  const doc = DOCUMENTS[documentId];
  if (!doc) return send(res, 404, { detail: "Document not found" });

  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });

  const terminal = ["EXTRACTED", "NEEDS_REVIEW", "FAILED"].includes(doc.status);
  if (terminal) {
    res.write(
      `event: status\ndata: ${JSON.stringify({
        documentId,
        organizationId: "org-1",
        status: doc.status,
        detail: doc.failureReason,
      })}\n\n`,
    );
    res.end();
    return;
  }

  const subscribers = eventSubscribers.get(documentId) ?? [];
  subscribers.push(res);
  eventSubscribers.set(documentId, subscribers);
  req.on("close", () => {
    const remaining = (eventSubscribers.get(documentId) ?? []).filter((s) => s !== res);
    eventSubscribers.set(documentId, remaining);
  });
}

function handleDashboardSummary(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  send(res, 200, DASHBOARD_SUMMARY);
}

function parseExpenseSort(sort) {
  let sortField = "date";
  let sortDir = "desc";
  if (!sort) {
    return { sortField, sortDir, error: null };
  }
  const parts = sort.split(",");
  sortField = parts[0];
  if (!["date", "amount"].includes(sortField)) {
    return { error: `Unknown sort field: ${sortField}` };
  }
  if (parts.length >= 2) {
    sortDir = parts[1];
    if (!["asc", "desc"].includes(sortDir)) {
      return { error: `Unknown sort direction: ${sortDir}` };
    }
  }
  return { sortField, sortDir, error: null };
}

function handleExpensesList(req, res, isAuthed, url) {
  if (!isAuthed) return send(res, 401, {});

  const status = url.searchParams.get("status");
  if (status && !["POSTED", "NEEDS_REVIEW"].includes(status)) {
    return send(res, 400, { detail: `Unknown status: ${status}` });
  }
  // Mirrors ExpenseListQuery.parse's split(",", 2) exactly: "date," has a present-but-empty
  // second part (parts.length === 2), which the real API 400s on just like "date,bogus" — only
  // a genuinely absent sort param (parts.length === 1) defaults the direction to desc.
  const { sortField, sortDir, error } = parseExpenseSort(url.searchParams.get("sort"));
  if (error) {
    return send(res, 400, { detail: error });
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

  // Matches ExpenseController's @RequestParam(defaultValue = "20") — a caller that omits `size`
  // should see the real API's actual default, not "everything," or an e2e test could pass here
  // while silently relying on a page size the real api would never give it.
  const size = Number(url.searchParams.get("size") ?? 20);
  send(res, 200, results.slice(0, size));
}

function handleCategories(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  send(res, 200, CATEGORIES);
}

function handleExpenseDetail(req, res, isAuthed, expenseId) {
  if (!isAuthed) return send(res, 401, {});
  const expense = EXPENSES.find((e) => e.id === expenseId);
  if (!expense) return send(res, 404, { detail: "Expense not found" });
  const document = DOCUMENTS[expense.documentId];
  send(res, 200, {
    ...expense,
    ledgerEntries: ledgerEntriesFor(expense),
    document: {
      id: document.id,
      filename: document.filename,
      contentType: document.contentType,
      sizeBytes: document.sizeBytes,
      status: document.status,
      proposal: document.proposal,
      failureReason: document.failureReason,
      createdAt: document.createdAt,
    },
  });
}

function handleDocumentContent(req, res, isAuthed, documentId) {
  if (!isAuthed) return send(res, 401, {});
  const document = Object.values(DOCUMENTS).find((d) => d.id === documentId);
  if (!document) return send(res, 404, { detail: "Document not found" });
  res.writeHead(200, {
    "content-type": document.contentType,
    "content-disposition": `attachment; filename="${document.filename}"`,
    "content-length": document.bytes.byteLength,
  });
  res.end(document.bytes);
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);
  const auth = req.headers.authorization;
  const isAuthed = auth === `Bearer ${TOKENS.accessToken}`;

  if (req.method === "POST" && url.pathname === "/api/v1/auth/login") {
    return handleLogin(req, res);
  }
  if (req.method === "POST" && url.pathname === "/api/v1/auth/refresh") {
    return handleRefresh(req, res);
  }
  if (req.method === "POST" && url.pathname === "/api/v1/documents") {
    return handleUpload(req, res, isAuthed);
  }

  const eventsMatch = url.pathname.match(/^\/api\/v1\/documents\/([^/]+)\/events$/);
  if (eventsMatch) {
    return handleDocumentEvents(req, res, isAuthed, eventsMatch[1]);
  }
  if (url.pathname === "/api/v1/dashboard/summary") {
    return handleDashboardSummary(req, res, isAuthed);
  }
  if (url.pathname === "/api/v1/expenses") {
    return handleExpensesList(req, res, isAuthed, url);
  }
  if (url.pathname === "/api/v1/categories") {
    return handleCategories(req, res, isAuthed);
  }

  const detailMatch = url.pathname.match(/^\/api\/v1\/expenses\/([^/]+)\/detail$/);
  if (detailMatch) {
    return handleExpenseDetail(req, res, isAuthed, detailMatch[1]);
  }

  const contentMatch = url.pathname.match(/^\/api\/v1\/documents\/([^/]+)\/content$/);
  if (contentMatch) {
    return handleDocumentContent(req, res, isAuthed, contentMatch[1]);
  }

  send(res, 404, { message: "not stubbed in e2e mock-api" });
});

server.listen(PORT, () => {
  console.log(`e2e mock-api listening on :${PORT}`);
});
