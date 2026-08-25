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
    { categoryId: "cat-1", categoryName: "Software", currency: "EUR", amountMinor: 4200000 },
    { categoryId: "cat-2", categoryName: "Travel", currency: "EUR", amountMinor: 2800000 },
    { categoryId: "cat-3", categoryName: "Office supplies", currency: "EUR", amountMinor: 1421300 },
  ],
  monthlySeries: [
    { month: "2026-02", currency: "EUR", amountMinor: 6000000 },
    { month: "2026-03", currency: "EUR", amountMinor: 7200000 },
    { month: "2026-04", currency: "EUR", amountMinor: 5100000 },
    { month: "2026-05", currency: "EUR", amountMinor: 8900000 },
    { month: "2026-06", currency: "EUR", amountMinor: 9569000 },
    { month: "2026-07", currency: "EUR", amountMinor: 8421300 },
  ],
  reviewQueueCount: 3,
  documentsProcessedToday: 17,
  alertCount: 2,
  recentAlerts: [],
};

const ALERTS_SEED = [
  {
    id: "alert-1", expenseId: "exp-1", categoryId: "cat-2", period: "2026-07", currency: "EUR",
    alertType: "BUDGET_THRESHOLD", thresholdPercent: 80, spentMinor: "840000", limitMinor: "1000000",
    historyCount: null, zScore: null, budgetBurnRate: 0.84, explanation: null, model: null,
    createdAt: "2026-07-24T10:00:00Z", categorizationConfidence: null,
    matchedExpenseId: null, duplicateTier: null, matchedExpense: null, triggeringExpense: null,
    title: "Travel nearing its budget", read: false, dismissed: false,
  },
  {
    id: "alert-2", expenseId: "exp-2", categoryId: "cat-1", period: "2026-07", currency: "EUR",
    alertType: "ANOMALY_HIGH", thresholdPercent: null, spentMinor: null, limitMinor: null,
    historyCount: 32, zScore: 3.2, budgetBurnRate: 0.42,
    explanation: "Spend is unusual for this category.", model: "gpt-test",
    createdAt: "2026-07-23T09:00:00Z", categorizationConfidence: null,
    matchedExpenseId: null, duplicateTier: null, matchedExpense: null, triggeringExpense: null,
    title: "Unusual spending detected", read: true, dismissed: false,
  },
  {
    id: "alert-3", expenseId: "exp-3", categoryId: "cat-3", period: "2026-07", currency: "EUR",
    alertType: "LOW_CONFIDENCE", thresholdPercent: null, spentMinor: null, limitMinor: null,
    historyCount: null, zScore: null, budgetBurnRate: null, explanation: null, model: null,
    createdAt: "2026-07-22T08:00:00Z", categorizationConfidence: 0.42,
    matchedExpenseId: null, duplicateTier: null, matchedExpense: null, triggeringExpense: null,
    title: "Low-confidence categorization needs review", read: false, dismissed: false,
  },
  {
    id: "alert-4", expenseId: "exp-2", categoryId: "cat-1", period: "2026-07", currency: "EUR",
    alertType: "DUPLICATE_SUSPECTED", thresholdPercent: null, spentMinor: null, limitMinor: null,
    historyCount: null, zScore: null, budgetBurnRate: null, explanation: null, model: null,
    createdAt: "2026-07-21T07:00:00Z", categorizationConfidence: null,
    matchedExpenseId: "exp-3", duplicateTier: "CONFIRMED",
    matchedExpense: { vendor: "Office Depot", amountMinor: "12800", currency: "EUR", createdAt: "2026-07-12T09:00:00Z" },
    triggeringExpense: { vendor: "Office Depot", amountMinor: "89900", currency: "EUR", createdAt: "2026-07-21T07:00:00Z" },
    title: "Office Depot may have been billed twice", read: false, dismissed: false,
  },
];
let ALERTS = JSON.parse(JSON.stringify(ALERTS_SEED));

let budgetCounter = 5;
const BUDGETS = [
  { id: "budget-1", categoryId: "cat-1", period: "2026-07", limitMinor: "500000", currency: "EUR", spentMinor: "0", burnRate: 0, status: "ON_TRACK", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" },
  { id: "budget-2", categoryId: "cat-2", period: "2026-07", limitMinor: "1000000", currency: "EUR", spentMinor: "790000", burnRate: 0.79, status: "ON_TRACK", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" },
  { id: "budget-3", categoryId: "cat-3", period: "2026-07", limitMinor: "1000000", currency: "EUR", spentMinor: "840000", burnRate: 0.84, status: "NEAR_THRESHOLD", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" },
  { id: "budget-4", categoryId: "cat-1", period: "2026-08", limitMinor: "1000000", currency: "EUR", spentMinor: "1000000", burnRate: 1, status: "OVER_BUDGET", createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z" },
];
const BUDGETS_SEED = JSON.parse(JSON.stringify(BUDGETS));

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
  const stages = status === "PROCESSING" ? [[2, "EXTRACTING", "Extracting document data"]] : status === "EXTRACTED" ? [[3, "CATEGORIZING", "Categorizing expense"], [4, "DRAFTING_LEDGER", "Drafting ledger entries"], [5, "POSTED", "Expense posted to the ledger"]] : [[3, "FAILED", detail ?? "Processing failed"]];
  for (const [id, stage, activityDetail] of stages) for (const res of eventSubscribers.get(documentId) ?? []) {
    res.write(`id: ${id}\nevent: activity\ndata: ${JSON.stringify({ id, stage, detail: activityDetail, createdAt: new Date().toISOString() })}\n\n`);
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

function handleRegister(req, res) {
  readBody(req)
    .then((body) => {
      const { fullName, company, email, password } = JSON.parse(body.toString("utf-8"));
      if (!fullName || !company || !email || !password || password.length < 12) {
        return send(res, 400, { message: "Complete every field and use a 12-character password." });
      }
      return send(res, 201, TOKENS);
    })
    .catch(() => send(res, 400, { message: "Invalid request body." }));
}

function handleRefresh(req, res) {
  send(res, 200, TOKENS);
}

function handleMe(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  send(res, 200, {
    userId: "user-1",
    fullName: "Elif Kaya",
    email: "owner@example.com",
    organizationId: "org-1",
    organizationName: "Northwind Co.",
    baseCurrency: "EUR",
  });
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
  res.write(`id: 1\nevent: activity\ndata: ${JSON.stringify({ id: 1, stage: "UPLOADED", detail: "Document uploaded", createdAt: doc.createdAt })}\n\n`);

  const terminal = ["EXTRACTED", "NEEDS_REVIEW", "FAILED"].includes(doc.status);
  if (terminal) {
    const stage = doc.status === "EXTRACTED" ? "POSTED" : "FAILED";
    res.write(`id: 5\nevent: activity\ndata: ${JSON.stringify({ id: 5, stage, detail: doc.failureReason, createdAt: doc.createdAt })}\n\n`);
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

function handleBudgets(req, res, isAuthed, url) {
  if (!isAuthed) return send(res, 401, {});
  if (req.method === "GET") return send(res, 200, BUDGETS);

  const budgetMatch = url.pathname.match(/^\/api\/v1\/budgets\/([^/]+)$/);
  const budgetId = budgetMatch?.[1];
  readBody(req).then((body) => {
    let input;
    try { input = JSON.parse(body.toString("utf-8")); } catch { return send(res, 400, { detail: "Invalid request body" }); }
    if (!input.categoryId || !/^\d{4}-(0[1-9]|1[0-2])$/.test(input.period) || !/^\d+$/.test(input.limitMinor) || BigInt(input.limitMinor) <= 0n || !/^[A-Z]{3}$/.test(input.currency)) {
      return send(res, 400, { detail: "Invalid budget input" });
    }
    const existing = budgetId ? BUDGETS.find((budget) => budget.id === budgetId) : undefined;
    if (budgetId && !existing) return send(res, 404, { detail: "Budget not found" });
    const duplicate = BUDGETS.find((budget) => budget.id !== budgetId && budget.categoryId === input.categoryId && budget.period === input.period && budget.currency === input.currency);
    if (duplicate) return send(res, 409, { detail: "A budget already exists for this category, period and currency" });
    const next = { id: existing?.id ?? `budget-${budgetCounter++}`, ...input, spentMinor: existing?.spentMinor ?? 0, burnRate: existing?.burnRate ?? 0, status: existing?.status ?? "ON_TRACK", createdAt: existing?.createdAt ?? new Date().toISOString(), updatedAt: new Date().toISOString() };
    if (existing) Object.assign(existing, next); else BUDGETS.push(next);
    send(res, existing ? 200 : 201, next);
  }).catch(() => send(res, 500, { detail: "Budget save failed" }));
}

function handleBudgetDelete(req, res, isAuthed, id) {
  if (!isAuthed) return send(res, 401, {});
  const index = BUDGETS.findIndex((budget) => budget.id === id);
  if (index === -1) return send(res, 404, { detail: "Budget not found" });
  BUDGETS.splice(index, 1);
  res.writeHead(204);
  res.end();
}

function handleAlertsList(req, res, isAuthed, url) {
  if (!isAuthed) return send(res, 401, {});
  const type = url.searchParams.get("type");
  const validTypes = ["BUDGET_THRESHOLD", "ANOMALY_HIGH", "LOW_CONFIDENCE"];
  if (type && !validTypes.includes(type)) {
    return send(res, 400, { detail: `Unknown alert type: ${type}` });
  }
  let visible = ALERTS.filter((a) => !a.dismissed);
  if (type) visible = visible.filter((a) => a.alertType === type);
  send(res, 200, visible);
}

function handleAlertsUnreadCount(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  const unreadCount = ALERTS.filter((a) => !a.dismissed && !a.read).length;
  send(res, 200, { unreadCount });
}

function handleAlertRead(req, res, isAuthed, id) {
  if (!isAuthed) return send(res, 401, {});
  const alert = ALERTS.find((a) => a.id === id);
  if (!alert) return send(res, 404, { detail: "Alert not found" });
  alert.read = true;
  res.writeHead(204);
  res.end();
}

function handleAlertsReadAll(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  for (const alert of ALERTS) {
    if (!alert.dismissed) alert.read = true;
  }
  res.writeHead(204);
  res.end();
}

function handleAlertDismiss(req, res, isAuthed, id) {
  if (!isAuthed) return send(res, 401, {});
  const alert = ALERTS.find((a) => a.id === id);
  if (!alert) return send(res, 404, { detail: "Alert not found" });
  alert.dismissed = true;
  res.writeHead(204);
  res.end();
}

function handleResetAlerts(req, res) {
  ALERTS = JSON.parse(JSON.stringify(ALERTS_SEED));
  send(res, 200, ALERTS);
}

function handleResetBudgets(req, res) {
  BUDGETS.splice(0, BUDGETS.length, ...JSON.parse(JSON.stringify(BUDGETS_SEED)));
  budgetCounter = 5;
  send(res, 200, BUDGETS);
}

// --- Policies (M9.7) ---

const POLICY_CHUNKS = {
  "policy-1": [
    { index: 0, text: "This policy applies to every employee who incurs costs on behalf of Northwind Co." },
    { index: 1, text: "Meals taken while travelling are reimbursed on actuals up to 50 EUR per day." },
    { index: 2, text: "An itemised receipt is required for any single expense of 25 EUR or more." },
  ],
};

let policyCounter = 3;
const POLICIES = [
  {
    id: "policy-1", filename: "expense-policy-2026.pdf", status: "EMBEDDED", failureReason: null,
    createdAt: "2026-08-12T00:00:00Z", chunkCount: 3,
  },
  {
    id: "policy-2", filename: "procurement-handbook-v4.pdf", status: "FAILED",
    failureReason: "pdf_text_extraction_empty: no text layer found on pages 1-48 (scanned image)",
    createdAt: "2026-07-28T00:00:00Z", chunkCount: 0,
  },
];
const POLICIES_SEED = JSON.parse(JSON.stringify(POLICIES));
const POLICY_CHUNKS_SEED = JSON.parse(JSON.stringify(POLICY_CHUNKS));

function handlePoliciesList(req, res, isAuthed) {
  if (!isAuthed) return send(res, 401, {});
  send(res, 200, POLICIES);
}

function handlePolicyGet(req, res, isAuthed, id) {
  if (!isAuthed) return send(res, 401, {});
  const policy = POLICIES.find((p) => p.id === id);
  if (!policy) return send(res, 404, { detail: "Policy document not found" });
  send(res, 200, policy);
}

function handlePolicyChunks(req, res, isAuthed, id) {
  if (!isAuthed) return send(res, 401, {});
  const policy = POLICIES.find((p) => p.id === id);
  if (!policy) return send(res, 404, { detail: "Policy document not found" });
  send(res, 200, POLICY_CHUNKS[id] ?? []);
}

function handlePolicyUpload(req, res, isAuthed) {
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
        return send(res, 415, { detail: "Uploaded policy document is empty" });
      }
      const detected = detectContentType(parsed.bytes);
      if (detected !== "application/pdf") {
        return send(res, 415, { detail: "Policy documents must be PDF" });
      }
      const id = `policy-upload-${policyCounter++}`;
      const document = {
        id, filename: parsed.filename, status: "EMBEDDED", failureReason: null,
        createdAt: new Date().toISOString(), chunkCount: 2,
      };
      POLICIES.unshift(document);
      POLICY_CHUNKS[id] = [
        { index: 0, text: "This addendum takes effect immediately upon upload." },
        { index: 1, text: "It supersedes the corresponding section of the base policy." },
      ];
      send(res, 201, document);
    })
    .catch(() => send(res, 500, { detail: "Policy upload failed" }));
}

function handleResetPolicies(req, res) {
  POLICIES.splice(0, POLICIES.length, ...JSON.parse(JSON.stringify(POLICIES_SEED)));
  Object.keys(POLICY_CHUNKS).forEach((key) => delete POLICY_CHUNKS[key]);
  Object.assign(POLICY_CHUNKS, JSON.parse(JSON.stringify(POLICY_CHUNKS_SEED)));
  policyCounter = 3;
  send(res, 200, POLICIES);
}

function handleExpenseDetail(req, res, isAuthed, expenseId) {
  if (!isAuthed) return send(res, 401, {});
  const expense = EXPENSES.find((e) => e.id === expenseId);
  if (!expense) return send(res, 404, { detail: "Expense not found" });
  const document = DOCUMENTS[expense.documentId];
  send(res, 200, {
    ...expense,
    invoiceNumber: "INV-2026-0714",
    documentDate: "2026-07-14",
    taxMinor: "1200",
    activity: [
      { id: 1, stage: "UPLOADED", detail: "Document uploaded", createdAt: document.createdAt },
      { id: 2, stage: "EXTRACTING", detail: "Extracting document data", createdAt: document.createdAt },
      { id: 3, stage: "CATEGORIZING", detail: "Categorizing expense", createdAt: document.createdAt },
      { id: 4, stage: "DRAFTING_LEDGER", detail: "Drafting ledger entries", createdAt: document.createdAt },
      { id: 5, stage: expense.status === "POSTED" ? "POSTED" : "NEEDS_REVIEW", detail: expense.status === "POSTED" ? "Expense posted to the ledger" : "Expense needs review", createdAt: document.createdAt },
    ],
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
  if (req.method === "POST" && url.pathname === "/api/v1/auth/register") {
    return handleRegister(req, res);
  }
  if (req.method === "POST" && url.pathname === "/api/v1/auth/refresh") {
    return handleRefresh(req, res);
  }
  if (req.method === "GET" && url.pathname === "/api/v1/me") {
    return handleMe(req, res, isAuthed);
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

  if (url.pathname === "/api/v1/budgets" && (req.method === "GET" || req.method === "POST")) {
    return handleBudgets(req, res, isAuthed, url);
  }
  if (url.pathname === "/api/v1/test/reset-budgets" && req.method === "POST") {
    return handleResetBudgets(req, res);
  }
  if (url.pathname === "/api/v1/alerts" && req.method === "GET") {
    return handleAlertsList(req, res, isAuthed, url);
  }
  if (url.pathname === "/api/v1/alerts/unread-count" && req.method === "GET") {
    return handleAlertsUnreadCount(req, res, isAuthed);
  }
  if (url.pathname === "/api/v1/alerts/read-all" && req.method === "POST") {
    return handleAlertsReadAll(req, res, isAuthed);
  }
  if (url.pathname === "/api/v1/test/reset-alerts" && req.method === "POST") {
    return handleResetAlerts(req, res);
  }
  const alertReadMatch = url.pathname.match(/^\/api\/v1\/alerts\/([^/]+)\/read$/);
  if (alertReadMatch && req.method === "POST") {
    return handleAlertRead(req, res, isAuthed, alertReadMatch[1]);
  }
  const alertDismissMatch = url.pathname.match(/^\/api\/v1\/alerts\/([^/]+)\/dismiss$/);
  if (alertDismissMatch && req.method === "POST") {
    return handleAlertDismiss(req, res, isAuthed, alertDismissMatch[1]);
  }
  const budgetMatch = url.pathname.match(/^\/api\/v1\/budgets\/([^/]+)$/);
  if (budgetMatch && req.method === "PUT") {
    return handleBudgets(req, res, isAuthed, url);
  }
  if (budgetMatch && req.method === "DELETE") {
    return handleBudgetDelete(req, res, isAuthed, budgetMatch[1]);
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

  if (url.pathname === "/api/v1/policies" && req.method === "GET") {
    return handlePoliciesList(req, res, isAuthed);
  }
  if (url.pathname === "/api/v1/policies" && req.method === "POST") {
    return handlePolicyUpload(req, res, isAuthed);
  }
  if (url.pathname === "/api/v1/test/reset-policies" && req.method === "POST") {
    return handleResetPolicies(req, res);
  }
  const policyChunksMatch = url.pathname.match(/^\/api\/v1\/policies\/([^/]+)\/chunks$/);
  if (policyChunksMatch) {
    return handlePolicyChunks(req, res, isAuthed, policyChunksMatch[1]);
  }
  const policyMatch = url.pathname.match(/^\/api\/v1\/policies\/([^/]+)$/);
  if (policyMatch) {
    return handlePolicyGet(req, res, isAuthed, policyMatch[1]);
  }

  send(res, 404, { message: "not stubbed in e2e mock-api" });
});

server.listen(PORT, () => {
  console.log(`e2e mock-api listening on :${PORT}`);
});
