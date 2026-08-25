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

> **Split 2026-07-28 into M7a (API) and M7b (UI).** The six screens below need roughly seven
> endpoints that do not exist — `api` today has only `POST /expenses` and `GET /expenses/{id}`:
> no list, no dashboard aggregates, no review approve/correct, no ledger-entry read model, no
> document byte serving, no SSE. As one milestone this was 12+ tasks with nothing verifiable
> until the end. M7a is `apps/api` (demo: `cd apps/api && ./mvnw clean verify`), M7b is
> `apps/web` (demo: the browser loop below). Task lists live in the Brain at
> `projects/ledgerly/todo.md`.
>
> **Budgets moved to M8.** The design covers a Budgets screen, but the `budget` table, CRUD and
> evaluation are all M8 — the screen ships with its API, not before it. M7 shows the nav item
> disabled, as it already does for Alerts and Policies.
>
> Design handoff: `docs/design/m7/README.md` (mirrored from Claude Design project
> `0c8633c7-9c28-4d78-9089-80d5b3d3fc26`). Accent frozen to indigo/violet
> `oklch(0.5 0.16 265)`, light theme only. The prototype's `support.js` / `image-slot.js`
> runtime is a reference, not code to port.

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

## M9.5 — Deploy-readiness checklist

Blockers M10 would otherwise hit mid-deploy: config that assumes localhost, storage that
assumes a persistent disk, no visibility once something breaks in prod. Each task ships as its
own commit and is independently verifiable — M10 then only wires the platforms together.

- [x] T1 — Bridge `DATABASE_URL` from Render's `postgres://` connection string to the
  `jdbc:postgresql://` form Spring's `datasource.url` requires.
  **Test:** given a `postgres://user:pass@host:5432/db` value, the resulting JDBC URL connects;
  a unit test on the parsing/composition function covers user/password with special characters
  (`@`, `:`, `/`) that must survive re-encoding.
- [x] T2 — Decide and implement the storage story for uploaded documents: accept ephemeral loss
  on Render's free/starter disk (documented tradeoff) or add an S3/R2-backed `Storage` adapter
  behind the existing port. **Decided: Cloudflare R2** (free tier: 10 GB storage, free egress,
  no expiry) via `R2StorageClient`, active only on the `prod` Spring profile;
  `LocalDiskStorage` stays default everywhere else.
  **Test:** a unit test against a mocked `S3Client` proves put/get/delete round-trip through
  `R2StorageClient` — bucket/key passed correctly, `NoSuchKeyException` mapped to
  `StorageKeyNotFoundException`, malformed keys rejected before any network call, and no
  credential ever appears in a thrown exception. (Plan originally called for a
  localstack/live-bucket integration test; the user chose mocked `S3Client` instead during
  execution — see `docs/decisions.md`.)
- [x] T3 — Add `CORS_ALLOWED_ORIGINS` config to `api`, driven by env var, replacing the
  hardcoded `localhost:3000` actuator-only CORS.
  **Test:** a request from an allowed origin gets `Access-Control-Allow-Origin` back; a request
  from an origin not in the configured list does not.
- [x] T4 — Sync `.env.example` with what `docker-compose.yml` actually references, so a fresh
  clone's `.env.example` → `.env` never silently misses a variable. **Correction during
  execution:** the plan's named variables (`SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`,
  `AI_RATE_LIMIT_REDIS_URL`) turned out to already be hardcoded literals inside
  `docker-compose.yml` itself (`redis`, `redis://redis:6379/0`) — never read from `.env` at
  all, so there was nothing to add for them. Running the actual diff found three different,
  real gaps: `API_URL`, `LOADTEST_DOCUMENT_QUOTA`, `LOADTEST_AI_QUOTA` (all present in compose
  with a `:-default` fallback, all undocumented). Added those instead.
  **Test:** `bash scripts/check-env-example.sh` (new — diffs `${VAR}` references in
  `docker-compose.yml` against keys in `.env.example`, exits 1 on any gap) now passes; wired
  into CI as its own job so this can't silently drift again. Verified the script actually
  fails by running it against a deliberately-broken copy of `.env.example` first.
- [x] T5 — Demo account/org seeding mechanism, covering every surface (dashboard, expenses,
  upload, review, budgets, alerts, policies) with a believable ~3-month-active organization —
  professional enough to hand a stranger the link. Not a Flyway migration, so it never runs
  against a real deployment by accident. Real `ai` calls happen once, offline, during T5's own
  build (record); the `demo` profile itself only replays recorded fixtures against the real
  service layer (replay) — no LLM cost or non-determinism on every boot/redeploy.

  - [x] T5.1 — Synthetic policy-document generator: extend the `generate.py` pattern
    (`apps/ai/evals/fixtures/synthetic/`) to produce 2–3 realistic expense-policy PDFs
    ("Travel & Expense Policy", "Software & Subscriptions Policy", "Client Entertainment
    Policy"), each with a few paragraphs of real-sounding rules a categorization citation could
    plausibly point to.
    **Test:** running the generator produces 2–3 PDFs; each opens and its text is extractable
    (a basic PDF-text-layer check, not just "file exists").
  - [x] T5.2 — Synthetic invoice generator: extend the same generator to produce ~18–24
    invoices across 6 vendors × ~4 variations, dated across the last 3 months, covering the
    scenario mix T5.5 needs (normal postings, a couple of low-confidence extractions, a near-
    duplicate pair, a scenario shaped to trip anomaly detection, and enough same-category spend
    in one month to cross a budget threshold).
    **Test:** generator produces the expected file count; each PDF's text layer is extractable;
    a manifest (vendor/date/amount/currency per file, mirroring `quality-manifest.json`'s
    shape) is written alongside them.
  - [x] T5.3 — Record policy embeddings: a one-off script/test harness uploads T5.1's PDFs
    through the real `PolicyUploadService` → real `ai` embedding call, and persists the
    resulting chunks+embeddings as a fixture (JSON) under
    `apps/api/src/main/resources/db/seed/`. Run once by us during this build, not by CI or by
    the running application.
    **Test:** the fixture file exists, is valid JSON, and its chunk count matches what
    `PolicyUploadService` reported when it ran.
  - [x] T5.4 — Record invoice extraction+categorization: same pattern as T5.3, against T5.2's
    invoices through the real `DocumentUploadService` → real `ai` extraction/categorization
    call (with T5.3's policy chunks already indexed, so recorded citations are real) →
    persisted as a fixture.
    **Test:** fixture exists, valid JSON, one entry per T5.2 invoice, each entry's
    vendor/amount/currency matches T5.2's manifest.
  - [x] T5.5 — `demo` Spring profile seed runner: reads T5.3/T5.4's fixtures and replays them
    through the real service layer (`DocumentUploadService`, `ExpensePostingService`/
    `ExpenseReviewService`, `BudgetService`, `AlertStateService`) — no LLM call at runtime.
    Creates one org, one demo user, all policy docs, all invoices/expenses. Shapes the mix so
    three alert types fire at least once: `BUDGET_THRESHOLD`, `ANOMALY_HIGH`,
    `DUPLICATE_SUSPECTED`. **Correction during T5.4:** `LOW_CONFIDENCE` dropped from scope —
    the real categorization LLM systematically returns ≥0.9 confidence even for a
    deliberately vendor-less, category-ambiguous invoice (multiple content variants tried; see
    `docs/decisions.md`), so there is no genuine (non-faked) way to make this fixture cross the
    0.7 threshold. Review queue will accordingly be empty rather than 2–3 pending items —
    every recorded expense posts.
    **Test:** starting with `SPRING_PROFILES_ACTIVE=demo` twice in a row is idempotent (second
    run creates nothing new, doesn't error); starting without the profile creates nothing; the
    seeded user can log in; dashboard, expenses, budgets, alerts, and policies pages each show
    non-empty, coherent data; all three alert types (`BUDGET_THRESHOLD`, `ANOMALY_HIGH`,
    `DUPLICATE_SUSPECTED`) are present at least once.
  - [x] T5.6 — End-to-end demo walkthrough check: with the `demo` profile running, open every
    page (dashboard, expenses list + detail, upload, review, budgets, alerts, policies +
    policy detail) in a real browser and confirm nothing errors, no placeholder/lorem-ipsum
    text, no broken document preview.
    **Test:** manual browser pass (or Playwright script reusing existing e2e patterns) hits
    every route above with the seeded session; zero console errors; document preview renders
    for at least one uploaded invoice.
- [x] T6 — Minimal error tracking: wire Sentry's free tier (5,000 events/mo) into `api`, `ai`,
  and `web`, so a prod exception is visible without grepping platform logs.
  **Test:** a deliberately thrown test exception in each service appears in the Sentry project
  within the CI/manual verification step; confirm no request body, document content, or secret
  value is included in the captured event (same PII bar as M9's structured-logging review).
- [x] T7 — Secrets audit: confirm no secret is committed anywhere in git history for
  `JWT_SECRET`, `AI_SERVICE_TOKEN`, `AI_LLM_API_KEY`, `AI_EMBEDDING_API_KEY`,
  `POSTGRES_PASSWORD`; write down in `docs/decisions.md` which platform secret store holds each
  one (Render env vars vs Vercel env vars) and confirm no `sync: false` blueprint value was
  filled with a real secret checked into `render.yaml`.
  **Test:** `git log -p | grep` (or a secret-scanning tool run once) against the four env var
  names above returns nothing outside `.env.example`'s placeholder values; `render.yaml` (once
  written in M10) contains no literal secret value.
- [x] T8 — Production log verbosity pass: confirm `api` and `ai` default to `INFO` (not
  `DEBUG`) outside local/dev profiles, and re-confirm the M9 PII rule still holds against
  whatever T6's Sentry breadcrumbs add.
  **Test:** starting each service without a dev/local profile active logs no `DEBUG`-level
  lines during a normal request; a document upload's structured log line contains no filename,
  file content, or extracted field values (same assertion style as the existing M9 PII test).
- [x] T9 — SEO/meta pass on `web`: page `<title>`, meta description, and one OG image for link
  previews (not a full milestone — three files, per the decision to fold this into M9.5 rather
  than open a dedicated milestone).
  **Test:** `curl` the built page and grep for `<title>`, `<meta name="description">`, and
  `<meta property="og:image">` all present and non-empty.
- [x] T10 — Free-tier uptime awareness: document (not necessarily automate) that Render's free
  web services spin down after 15 minutes idle, and either accept the cold-start UX for the
  demo link or add a free external uptime pinger (e.g. UptimeRobot's free tier) to keep `api`
  and `ai` warm during demo windows. **Decided: UptimeRobot** (see docs/decisions.md) — actual
  monitor setup deferred to M10, since it needs the real Render URLs that only exist once
  `render.yaml` is deployed.
  **Test:** decision recorded in `docs/decisions.md` with the tradeoff stated; if a pinger is
  configured, one successful ping observed in its dashboard/logs. (Second half applies once
  M10 wires up the actual UptimeRobot monitors.)

**Demo**
```bash
# T1: JDBC bridge unit test
cd apps/api && ./mvnw test -Dtest=DatabaseUrlBridgeTest
# T5: demo seed idempotency
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run &  # run twice, assert no duplicate org
# T6: trigger a test exception in each service, confirm it lands in Sentry
```

**Done when:** every task above is checked, `api`/`ai`/`web` all boot with prod-shaped env vars
(no `localhost` assumptions left), and there is a documented answer — not a silent gap — for
storage durability, secrets custody, and uptime behavior on the chosen tier.

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
| 6 | M9.5, M10 + buffer |

Buffer is real, not decorative. M5 (extraction accuracy) and M7 (frontend polish) are the two
most likely to overrun.
