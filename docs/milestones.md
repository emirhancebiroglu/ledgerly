# Milestones

Ten milestones, 4–6 weeks at roughly 10–15 hours per week. Every milestone ends with a command
that either works or does not — no milestone is "done" on the strength of an opinion.

**Ordering rule:** the trust boundary (M3) lands before any agent code (M5). Validation logic
written after the thing it validates tends to be shaped around the bug instead of the spec.

**Cut order if time runs short:** M9, then the RAG half of M6, then M8's anomaly agent. M1–M7
plus M10 is a complete, deployable, demonstrable product on its own.

---

## M1 — Skeleton and infrastructure

Three services boot together and answer health checks. Nothing else.

- Monorepo layout: `apps/api`, `apps/ai`, `apps/web`, `infra/`.
- Spring Boot 3 project, Java 21 preferred (17 is fine — no virtual-thread dependency yet).
  `GET /actuator/health`.
- FastAPI project, Python 3.12. `GET /health`.
- Next.js App Router + TypeScript + Tailwind + shadcn/ui. A page that fetches and shows both
  service health values.
- `infra/docker-compose.yml`: postgres (pgvector image), api, ai, web.
- GitHub Actions: build + test all three on push and PR.
- `.gitignore`, `.env.example`, `.editorconfig`.

**Demo**
```bash
docker compose -f infra/docker-compose.yml up -d
curl -f localhost:8080/actuator/health && curl -f localhost:8000/health && open localhost:3000
```

**Done when:** all three respond, the dashboard shows both as healthy, and CI is green on a PR.

---

## M2 — Ledger domain and schema

The financial core. No HTTP, no AI — just a domain model that cannot represent an invalid state.

- Flyway migrations: `organization`, `user`, `account`, `ledger_transaction`, `ledger_entry`,
  `category`.
- `amount_minor BIGINT` + `currency CHAR(3)`. Zero floating-point columns.
- A `Money` value object. Arithmetic, currency-mismatch rejection, no public zero-arg construction.
- `LedgerTransaction` refuses to construct unbalanced. Deferred database constraint enforces the
  same rule independently.
- Immutability: no update/delete path for `ledger_entry`. Corrections are reversing entries.
- Chart-of-accounts seed data.
- Testcontainers integration tests + property-based tests for balancing and money arithmetic.
- CI check: fail the build if a migration introduces `float`, `real`, or `double precision`.

**Demo**
```bash
cd apps/api && ./mvnw test -Dtest='Money*,Ledger*'
./mvnw test -Dtest=LedgerBalanceConstraintIT   # unbalanced insert must be rejected by the DB
```

**Done when:** an unbalanced transaction is rejected by both the domain and the database, proven
by tests, and the reversing-entry path is covered.

---

## M3 — Auth, idempotency, audit trail

The three cross-cutting concerns. Deliberately before any agent code.

- JWT auth, org-scoped. Register/login/refresh.
- Authorization enforced at the service layer, not only in controllers.
- Idempotency filter/interceptor over mutating endpoints, implementing the decision table in
  `architecture.md` §3 including the 409-on-hash-mismatch case.
- Audit trail written inside the same database transaction as the change it records.
- Correlation id generated at the edge, propagated to `ai`, present in every structured log line.
- Tenant isolation test: org A cannot reach org B's data through any endpoint.
- Global error handling — no stack traces or internal detail in responses.

**Demo**
```bash
# same key twice → one effect, identical response body
curl -X POST localhost:8080/api/v1/expenses -H 'Idempotency-Key: k1' -d @fixture.json
curl -X POST localhost:8080/api/v1/expenses -H 'Idempotency-Key: k1' -d @fixture.json
# same key, changed payload → 409
curl -X POST localhost:8080/api/v1/expenses -H 'Idempotency-Key: k1' -d @other.json
```

**Done when:** the replay returns the original response with no second ledger entry, the mismatch
returns 409, and every one of those calls left an audit row.

---

## M4 — Documents and the `ai` contract

Upload works end to end and both services agree on the interface — with a stub on the far side.

- `POST /api/v1/documents` — multipart, magic-byte validation, size cap, opaque storage key.
- Document status lifecycle: `PENDING → PROCESSING → EXTRACTED | NEEDS_REVIEW | FAILED`.
- `ai` exposes `POST /extract` returning a schema-valid stubbed `ExtractionProposal`.
- Shared schemas checked into `docs/contracts/`, with contract tests on both sides.
- `LlmClient` port defined in `ai` with a `FakeLlmClient` implementation. **Q1 (provider) is
  decided here** — pick after trying two providers against ten real invoices.
- Validation layer in `api`: currency known, `total == sum(lines) + tax`, date within a sane
  range, amount under the org ceiling. Failure routes to `NEEDS_REVIEW` and writes nothing to the
  ledger. **This is the trust boundary and it must exist before a real model does.**

**Demo**
```bash
curl -X POST localhost:8080/api/v1/documents -H 'Idempotency-Key: d1' -F file=@samples/invoice.pdf
curl localhost:8080/api/v1/documents/{id}    # status EXTRACTED, proposal attached
# deliberately corrupt fixture → NEEDS_REVIEW, ledger untouched
```

**Done when:** a valid document reaches `EXTRACTED`, a malformed proposal reaches `NEEDS_REVIEW`,
and neither wrote a ledger entry yet.

---

## M5 — Extraction agent (real)

Swap the stub for a real multimodal model and measure it.

- LangGraph extraction graph: vision call → structured output → self-check pass on low-confidence
  fields.
- Concrete `LlmClient` adapter for the provider chosen in M4.
- Per-field confidence scores in the response.
- Retry with backoff, timeout, and a circuit breaker — a slow LLM must not hold an HTTP thread.
- **Eval set:** 20 real invoices with hand-written expected output. Accuracy per field is printed
  as a number.

**Demo**
```bash
cd apps/ai && python -m evals.extraction   # prints per-field accuracy table
curl -X POST localhost:8000/extract -F file=@samples/invoice.pdf | jq
```

**Done when:** the eval runs and reports ≥90% field accuracy on total, date, and currency. Below
that, iterate on prompt or provider before moving on — a wrong number in a ledger is worse than
no number.

---

## M6 — Categorization and policy RAG

- Org-scoped category taxonomy, CRUD.
- Policy document upload → chunk → embed → `policy_chunk` with pgvector.
- Categorization graph: retrieve relevant policy chunks + taxonomy → classify → return category
  *with the citation that justified it*.
- `POST /categorize` in `ai`, wired into the `api` processing flow.
- Full pipeline: upload → extract → categorize → build balanced transaction → persist expense +
  transaction + audit in one database transaction.
- Low-confidence categorizations go to a review queue instead of posting silently.

**Demo**
```bash
curl -X POST localhost:8080/api/v1/policies -F file=@samples/expense-policy.pdf
curl -X POST localhost:8080/api/v1/documents -H 'Idempotency-Key: d2' -F file=@samples/invoice.pdf
sleep 10
curl localhost:8080/api/v1/expenses/{id}   # category + policy citation + balanced ledger entry
psql -c 'SELECT SUM(CASE WHEN direction='"'"'DEBIT'"'"' THEN amount_minor ELSE -amount_minor END) FROM ledger_entry;'
# must be 0
```

**Done when:** an invoice becomes a categorized, cited, balanced ledger transaction with no manual
step, and the ledger sums to zero.

---

## M7 — Dashboard

The product becomes visible.

- Auth pages, protected routes.
- Upload with drag-and-drop and live status.
- Expense list: filter, sort, search; amounts formatted from minor units correctly.
- Charts: spend by category, spend over time. Read [dataviz](../../) guidance before choosing
  colors and chart forms.
- Expense detail: the document alongside the extracted fields and the ledger entries it produced.
- Review queue for low-confidence items, with an approve/correct action.
- Agent activity panel streaming steps over SSE.
- Responsive; no horizontal page scroll; keyboard accessible.

**Demo**
```bash
# in the browser: log in, upload an invoice, watch the agent panel stream,
# see it appear in the list and in the charts, open detail, approve a review item
```

**Done when:** the full loop is doable in the UI with no curl and no database access.

---

## M8 — Budgets and anomaly agent

- `budget` table: category + period + `limit_minor`. CRUD.
- Budget evaluation on every expense post; burn rate computed in code.
- Anomaly graph: **statistics in Python first** (z-score against 90-day category history, budget
  burn rate), then the LLM writes the explanation. The model never invents a number.
- Alert records surfaced on the dashboard.
- Threshold crossings (80%, 100%) produce alerts.

**Demo**
```bash
curl -X POST localhost:8080/api/v1/budgets -d '{"category":"SaaS","period":"2026-08","limitMinor":500000,"currency":"EUR"}'
# post expenses until the budget is exceeded
curl localhost:8080/api/v1/alerts   # threshold alert + anomaly explanation citing real numbers
```

**Done when:** an out-of-pattern expense produces an alert whose stated figures match what the
database actually holds. **First cut candidate if the schedule slips.**

---

## M9 — Hardening

- Rate limiting on upload and agent endpoints.
- OWASP pass against `architecture.md` §6 — run `/security-review`.
- Load test on the upload path; fix the N+1 queries it exposes.
- Structured logging review: no PII, no document contents, no secrets.
- Dependency vulnerability scan in CI.
- Graceful degradation — `ai` being down must not take `api` down.

**Demo**
```bash
cd apps/api && ./mvnw verify          # all tests, coverage report
./scripts/loadtest.sh                  # p95 latency printed
# stop the ai container: api still serves reads, uploads queue as PENDING
```

**Done when:** the security review has no unaddressed high findings and killing `ai` degrades
rather than breaks the system. **Second cut candidate.**

---

## M10 — Production deploy

- Dockerfiles: multi-stage, non-root user, pinned base images.
- Render: `api`, `ai`, managed PostgreSQL with pgvector.
- Vercel: `web`.
- GitHub Actions deploy on merge to `main`.
- Secrets in the platform's secret store. Never in the repository.
- Seeded demo account so the live link is explorable without signing up.
- README: architecture diagram, live link, screenshots, honest "what I would do next".
- 60–90 second demo recording.

**Demo**
```bash
curl -f https://<app>.onrender.com/actuator/health
# open the Vercel URL, log into the demo account, upload the sample invoice
```

**Done when:** a stranger with the link can upload an invoice and see it become a categorized
ledger entry, without any local setup.

---

## Schedule sketch

| Week | Milestones |
|---|---|
| 1 | M1, M2 |
| 2 | M3, M4 |
| 3 | M5, M6 |
| 4 | M7 |
| 5 | M8, M9 |
| 6 | M10 + buffer |

Buffer is real, not decorative. M5 (extraction accuracy) and M7 (frontend polish) are the two
most likely to overrun.
