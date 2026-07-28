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
  {
    id: "exp-6",
    documentId: "doc-6",
    vendor: "Rideshare Co.",
    categoryId: "cat-2",
    ledgerTransactionId: null,
    amountMinor: 3200,
    currency: "EUR",
    categorizationConfidence: 0.58,
    citation: "Amount mismatch vs OCR (58% confidence)",
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-19T11:00:00Z",
  },
  {
    id: "exp-7",
    documentId: "doc-7",
    vendor: "Already Resolved Vendor",
    categoryId: "cat-3",
    ledgerTransactionId: null,
    amountMinor: 9900,
    currency: "EUR",
    categorizationConfidence: 0.65,
    citation: null,
    // Simulates a race the e2e suite can drive on demand: approving this id returns 409 to
    // prove "already resolved elsewhere" is reported, not silently swallowed.
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-18T11:00:00Z",
  },
  // exp-8 through exp-11: each dedicated to exactly one review-queue mutation test (bulk
  // approve, single approve, keyboard, correct). e2e runs across two Playwright projects
  // (chromium + mobile-chromium) in parallel workers sharing this same in-memory mock server —
  // any test that mutates exp-3 ("Office Depot") would corrupt the read-only NEEDS_REVIEW
  // fixture every other spec file (T4/T5/T6's dashboard/expenses/expense-detail tests) depends
  // on staying untouched for the life of the whole `npm run e2e` run.
  {
    id: "exp-8",
    documentId: "doc-8",
    vendor: "Bulk Approve Target",
    categoryId: "cat-1",
    ledgerTransactionId: null,
    amountMinor: 5000,
    currency: "EUR",
    categorizationConfidence: 0.6,
    citation: null,
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-17T11:00:00Z",
  },
  {
    id: "exp-9",
    documentId: "doc-9",
    vendor: "Single Approve Target",
    categoryId: "cat-1",
    ledgerTransactionId: null,
    amountMinor: 6000,
    currency: "EUR",
    categorizationConfidence: 0.6,
    citation: null,
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-16T11:00:00Z",
  },
  {
    id: "exp-10",
    documentId: "doc-10",
    vendor: "Keyboard Target",
    categoryId: "cat-1",
    ledgerTransactionId: null,
    amountMinor: 7000,
    currency: "EUR",
    categorizationConfidence: 0.6,
    citation: null,
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-15T11:00:00Z",
  },
  {
    id: "exp-11",
    documentId: "doc-11",
    vendor: "Correct Target",
    categoryId: "cat-1",
    ledgerTransactionId: null,
    amountMinor: 8000,
    currency: "EUR",
    categorizationConfidence: 0.6,
    citation: null,
    status: "NEEDS_REVIEW",
    createdAt: "2026-07-14T11:00:00Z",
  },
];

// Deep-cloned at module load, before any test mutates EXPENSES — /api/v1/test/reset-expense
// restores one row from this snapshot so review-queue mutation tests are self-healing
// regardless of execution order across Playwright's multiple projects/workers sharing this one
// server process, instead of relying on every test touching a never-reused fixture id.
const EXPENSES_SEED = JSON.parse(JSON.stringify(EXPENSES));

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
  "doc-6": {
    id: "doc-6",
    filename: "rideshare-receipt.png",
    contentType: "image/png",
    sizeBytes: TINY_PNG.byteLength,
    status: "NEEDS_REVIEW",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-19T10:58:00Z",
    bytes: TINY_PNG,
  },
  "doc-7": {
    id: "doc-7",
    filename: "already-resolved-receipt.png",
    contentType: "image/png",
    sizeBytes: TINY_PNG.byteLength,
    status: "NEEDS_REVIEW",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-18T10:58:00Z",
    bytes: TINY_PNG,
  },
};

// doc-8..doc-11 back exp-8..exp-11 (each dedicated to one review-queue mutation test) —
// generated rather than hand-written since none of them need distinct fixture content, only to
// exist so handleExpenseDetail never 500s if a future test navigates to one of these rows.
for (let i = 8; i <= 11; i++) {
  DOCUMENTS[`doc-${i}`] = {
    id: `doc-${i}`,
    filename: `review-target-${i}-receipt.png`,
    contentType: "image/png",
    sizeBytes: TINY_PNG.byteLength,
    status: "NEEDS_REVIEW",
    proposal: null,
    failureReason: null,
    createdAt: "2026-07-13T10:58:00Z",
    bytes: TINY_PNG,
  };
}

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

function handleResetExpense(req, res, expenseId) {
  // Test-only endpoint — not part of the real api's contract. Restores one row to its seeded
  // state so a mutation test is unaffected by another project's worker having already resolved
  // the same fixture in this shared server process.
  const seed = EXPENSES_SEED.find((e) => e.id === expenseId);
  if (!seed) return send(res, 404, { detail: "No such seed expense" });
  const index = EXPENSES.findIndex((e) => e.id === expenseId);
  const restored = JSON.parse(JSON.stringify(seed));
  if (index === -1) {
    EXPENSES.push(restored);
  } else {
    EXPENSES[index] = restored;
  }
  send(res, 200, restored);
}

function resolveReviewItem(res, expenseId, mutate) {
  const expense = EXPENSES.find((e) => e.id === expenseId);
  if (!expense) return send(res, 404, { detail: "Expense not found" });
  if (expense.status !== "NEEDS_REVIEW") {
    // Mirrors ExpenseAlreadyResolvedException — approving/correcting something already
    // resolved (including by a second concurrent request) is a 409, not a silent no-op.
    return send(res, 409, { detail: "This expense has already been resolved" });
  }
  mutate(expense);
  expense.status = "POSTED";
  expense.ledgerTransactionId = `txn-${expense.id}`;
  send(res, 200, { ...expense });
}

function handleApprove(req, res, isAuthed, expenseId) {
  if (!isAuthed) return send(res, 401, {});
  if (!req.headers["idempotency-key"]) {
    return send(res, 400, { detail: "Idempotency-Key header is required" });
  }
  // exp-7 simulates a request that lost a race to a concurrent resolution elsewhere — always
  // 409s, regardless of how many times it's retried, so the e2e suite can drive this on demand
  // without needing two real concurrent requests.
  if (expenseId === "exp-7") {
    return send(res, 409, { detail: "This expense has already been resolved" });
  }
  resolveReviewItem(res, expenseId, () => {});
}

function handleCorrect(req, res, isAuthed, expenseId) {
  if (!isAuthed) return send(res, 401, {});
  if (!req.headers["idempotency-key"]) {
    return send(res, 400, { detail: "Idempotency-Key header is required" });
  }
  readBody(req)
    .then((body) => {
      let categoryId;
      try {
        categoryId = JSON.parse(body.toString("utf-8")).categoryId;
      } catch {
        return send(res, 400, { detail: "Invalid request body" });
      }
      if (!categoryId) {
        return send(res, 400, { detail: "categoryId is required" });
      }
      resolveReviewItem(res, expenseId, (expense) => {
        expense.categoryId = categoryId;
      });
    })
    .catch(() => send(res, 500, { detail: "Correct failed" }));
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

  const approveMatch = url.pathname.match(/^\/api\/v1\/expenses\/([^/]+)\/approve$/);
  if (req.method === "POST" && approveMatch) {
    return handleApprove(req, res, isAuthed, approveMatch[1]);
  }

  const correctMatch = url.pathname.match(/^\/api\/v1\/expenses\/([^/]+)\/correct$/);
  if (req.method === "POST" && correctMatch) {
    return handleCorrect(req, res, isAuthed, correctMatch[1]);
  }

  const contentMatch = url.pathname.match(/^\/api\/v1\/documents\/([^/]+)\/content$/);
  if (contentMatch) {
    return handleDocumentContent(req, res, isAuthed, contentMatch[1]);
  }

  const resetMatch = url.pathname.match(/^\/api\/v1\/test\/reset-expense\/([^/]+)$/);
  if (req.method === "POST" && resetMatch) {
    return handleResetExpense(req, res, resetMatch[1]);
  }

  send(res, 404, { message: "not stubbed in e2e mock-api" });
});

server.listen(PORT, () => {
  console.log(`e2e mock-api listening on :${PORT}`);
});
