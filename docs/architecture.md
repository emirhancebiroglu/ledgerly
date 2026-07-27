# Architecture

This document is the reference the implementation is checked against. If the code and this
document disagree, one of them is a bug — decide which, then fix it.

## 1. Guiding constraints

These are not preferences. Every design choice below traces back to one of them.

| # | Constraint | Consequence |
|---|---|---|
| C1 | Money is never approximate | No `float`/`double` anywhere near an amount. Storage is `BIGINT` minor units + ISO currency code; in-memory type is `BigDecimal`/`Money`. |
| C2 | Every write is safely retryable | Mutating endpoints require an `Idempotency-Key`. A replayed key returns the original response, never a second effect. |
| C3 | Nothing changes without a record | Every mutation appends to an immutable audit trail: actor, action, entity, before/after, timestamp, request id. |
| C4 | The books must balance | Ledger postings are double-entry. Debits equal credits per transaction, enforced in the domain *and* by a database constraint. |
| C5 | The LLM is never on the write path | `ai` returns *proposals*. `api` validates and posts. A hallucinated amount can be rejected; it cannot corrupt the ledger. |
| C6 | Correlated failure is visible | Every request carries a correlation id across `web → api → ai`, present in all structured logs. |

## 2. Service boundaries

### `api` — Spring Boot (system of record)

Owns: users, organizations, accounts, ledger entries, expenses, budgets, audit log,
idempotency records, document metadata.

Responsibilities:
- Authentication and authorization (JWT, org-scoped).
- Document upload → object storage → enqueue processing.
- Calling `ai` for extraction/categorization, validating the response, posting the entry.
- Budget evaluation and threshold checks.
- Serving all read models to `web`.

Never: calls an LLM directly, stores an unvalidated agent output as truth.

### `ai` — FastAPI (advisory only)

Owns: agent graphs, prompts, LLM adapters, the policy-document vector index.

Responsibilities:
- `POST /extract` — document bytes/URL in, structured `ExtractionProposal` out with per-field
  confidence.
- `POST /categorize` — expense + org taxonomy + policy context in, category proposal out.
- `POST /anomaly` — expense + recent history + budget state in, risk assessment out.
- Policy RAG over org-uploaded expense-policy documents (pgvector).

Never: writes to ledger tables, holds authoritative state, is trusted without validation.

Stateless per request except for the vector index, which is read-mostly.

### `web` — Next.js

Dashboard, upload flow, expense review queue, budget views, and an agent activity panel that
streams each agent step (thinking → tool → result) over SSE.

## 3. Data model

Money columns are `amount_minor BIGINT NOT NULL` plus `currency CHAR(3) NOT NULL`. There is no
column of type `float`, `real`, or `double precision` in this schema. A CI check greps the
migrations for those types and fails the build if one appears.

```
organization ──┬── user
               ├── account            (chart of accounts; type: ASSET|LIABILITY|EXPENSE|REVENUE|EQUITY)
               ├── document           (uploaded file metadata + storage key + status)
               ├── expense            (business-level record; links document + ledger_transaction)
               ├── ledger_transaction (header: date, description, org, created_by)
               │     └── ledger_entry (account_id, direction DEBIT|CREDIT, amount_minor, currency)
               ├── category           (org-scoped taxonomy)
               ├── budget             (category + period + limit_minor)
               ├── audit_log          (append-only)
               ├── idempotency_record (key, endpoint, request_hash, response, status, expires_at)
               └── policy_chunk       (text + embedding vector, for RAG)
```

### Double-entry invariant

A `ledger_transaction` is valid only if, per currency, the sum of `DEBIT` amounts equals the sum
of `CREDIT` amounts. Enforced twice:

1. In the domain — a `LedgerTransaction` cannot be constructed unbalanced.
2. In the database — a deferred constraint trigger rejects an unbalanced transaction at commit.

Ledger entries are **immutable**. A mistake is corrected with a reversing transaction, never an
`UPDATE` or `DELETE`. The tables carry no update or delete grants for the application role.

### Idempotency

`idempotency_record` is keyed on `(organization_id, key, endpoint)`. On a request:

- No record → insert `IN_PROGRESS`, run the handler, store the response, return it.
- Record `COMPLETED` with a matching request hash → return the stored response.
- Record `COMPLETED` with a *different* request hash → `409 Conflict` (key reuse with different
  payload is a client bug and must be loud).
- Record `IN_PROGRESS` → `409 Conflict`, retry later.

Records expire after 24h.

### Audit trail

Append-only. Written in the same transaction as the change it describes, so an audit gap is
impossible without a lost write. Captures actor, action, entity type/id, before/after JSON,
correlation id, and timestamp.

## 4. Expense processing flow

```
1. web       POST /documents            (multipart, Idempotency-Key)
2. api       store file, row → PENDING, return 202 + documentId
3. api       async: POST ai/extract
4. ai        LLM (vision) → ExtractionProposal { vendor, date, total, tax, currency,
                                                 lineItems[], confidence per field }
5. api       validate: currency known, total = sum(lines) + tax, date sane,
             amount within org limits. Fail → status NEEDS_REVIEW, no ledger write.
6. api       POST ai/categorize (proposal + org taxonomy + policy RAG context)
7. api       build balanced LedgerTransaction:
                 DEBIT  expense account (category)   amount_minor
                 CREDIT liability/cash account       amount_minor
8. api       POST ai/anomaly (expense + 90d history + budget state)
9. api       persist expense + transaction + audit rows in ONE database transaction
10. api      if budget threshold crossed or anomaly risk HIGH → alert record
11. web      SSE stream shows each step live; low-confidence fields land in a review queue
```

Steps 4, 6, 8 are advisory. Step 5 is the trust boundary: nothing an agent produced reaches
step 9 without passing deterministic validation.

## 5. Agent design (`ai`)

LangGraph, one graph per capability rather than one mega-graph — smaller state, easier to test,
independently versioned.

- **Extraction graph** — multimodal LLM call, structured-output schema, self-check pass that
  re-reads the document for fields it flagged low-confidence.
- **Categorization graph** — retrieve policy chunks (pgvector) + org taxonomy → classify →
  return category with the policy citation that justified it.
- **Anomaly graph** — deterministic statistics computed in Python (z-score against category
  history, budget burn rate) *first*, then the LLM writes the explanation. Numbers come from
  code, prose comes from the model.

### LLM provider

Deferred. `ai` defines an `LlmClient` port with `complete()` and `complete_vision()`; concrete
adapters (Gemini, Claude, others) are selected by configuration. The decision is made at M4 when
extraction accuracy can actually be measured on real invoices, not guessed at now.

## 6. Security

Threat model assumes multi-tenant data with financial value.

- Every query is org-scoped. Tenant isolation is tested explicitly — a test asserts org A cannot
  read org B's expenses through any endpoint.
- Uploads: type and magic-byte validated, size-capped, stored outside the web root under an
  opaque key, never served from a path built out of user input.
- Secrets come from the environment only. No key, token, or connection string is ever committed.
- Structured logging with amounts and vendor names, but no raw document contents and no PII in
  logs.
- Authorization is checked at the service layer, not only at the controller — an internal caller
  cannot bypass it.
- Rate limiting on upload and agent endpoints; LLM calls cost money and are a denial-of-wallet
  target.

## 7. Testing strategy

| Level | Scope |
|---|---|
| Unit | Money arithmetic, double-entry balancing, idempotency decision table, anomaly statistics |
| Integration | Repository + migration behavior against real PostgreSQL (Testcontainers) |
| Contract | `api` ↔ `ai` request/response schemas, verified from both sides |
| Agent eval | A fixture set of invoices with known-correct extractions; accuracy is a measured number, not an impression |
| E2E | Upload → ledger entry → dashboard, against docker-compose |

The double-entry invariant and the idempotency table get property-based tests. They are the two
places where a subtle bug is both most likely and most expensive.

## 8. Deployment

- **Render** — `api`, `ai`, and PostgreSQL, deployed from Dockerfiles.
- **Vercel** — `web`.
- **GitHub Actions** — on PR: build, test, lint all three apps. On merge to `main`: deploy.
- Flyway migrations run on `api` startup. Migrations are forward-only; a bad migration is fixed
  by a new migration.

## 9. Open questions

| # | Question | Decide by | Status |
|---|---|---|---|
| Q1 | LLM provider and model | **M5** (moved from M4) | Open |
| Q2 | Object storage — Render disk vs S3-compatible bucket | M4 | **Decided** |
| Q3 | Async processing — Spring `@Async` vs a real queue | **M5**, from observed latency | Open |
| Q4 | Multi-currency: convert at post time or store native and convert on read | M2 | **Decided** |

**Q1 moved to M5.** The plan was to choose a provider at M4 by running candidates against ten real
invoices. Measuring that needs the eval harness M5 builds; doing it at M4 would have meant
hand-grading or guessing, and a guess is exactly what the "decide by measurement" plan was written
to avoid. M4 ships the `LlmClient` port and a `FakeLlmClient` only — no real adapter is wired.

**Q2 decided at M4: local disk behind a `StorageClient` port.** `LocalDiskStorage` writes under an
opaque UUID key, outside any web-served directory. An S3 adapter is a new implementation of the
same port if Render's disk proves insufficient (revisit at M10), with no caller changes. Adding
MinIO and the AWS SDK to the milestone whose purpose was proving the `api` ↔ `ai` contract would
have bought nothing that port does not already buy.

**Q3 stays open; M4 processes synchronously.** The stub returns instantly, so there is no latency to
design around yet, and the real number first exists at M5. The status lifecycle already models
`PENDING → PROCESSING → …`, so going asynchronous later is a service-layer change, not a schema
change.
