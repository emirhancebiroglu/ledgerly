# Decisions

Newest entry on top. Append-only — a superseded decision stays, with `supersedes:` on the entry
that replaced it.

---

## 2026-08-26 — Replace Redis with in-process adapters for a single-instance deployment (M9.9)

**Context.** M10 planning surfaced a blocker M9.5's checklist missed: Redis is load-bearing in
both services — pub/sub SSE fan-out (`DocumentEventPublisher`, `DocumentActivityEventPublisher`,
`DocumentEventController` against a shared `RedisMessageListenerContainer`), auth and upload rate
limiting in `api`, and cost-bearing agent rate limiting in `ai`. Both rate limiters fail *closed*
(`RateLimitUnavailableException` / `RateLimitUnavailable`), so a deployment with no reachable
Redis does not degrade, it rejects every upload. Render's free tier has no managed Redis; its Key
Value offering is paid-only.

**Decision.** Extract a `RateLimiter` port and a `DocumentEventBroker` port, add in-process
adapters as the default, and keep the Redis adapters behind a profile that `docker-compose.yml`
continues to activate. Land it as its own milestone (M9.9) *before* M10, so deploy configuration
is never verified in the same pass as a write-path code change.

**Alternatives.**
- *Upstash Redis free tier* — rejected on a technical fact, not on cost: its free tier is
  HTTP/REST-based and does not support the persistent subscriber connection
  `RedisMessageListenerContainer` requires, so it cannot back SSE at all. It would have solved
  only the rate-limiting half while appearing to solve both.
- *Render Key Value (~$10/mo)* — works with zero code change and keeps the deployed system
  identical to what the existing 219 `api` tests cover. Rejected because it pays monthly to
  retain coordination machinery that a single-instance topology never exercises.
- *Delete rate limiting and SSE for the demo* — rejected outright; both are M7/M9 deliverables and
  removing them to fit a hosting tier would make the deployed system a weaker claim than the
  repository.

**Rationale.** Redis is here to coordinate *across instances*: pub/sub so a status change on
instance A reaches a browser streaming from instance B, and shared counters so a client cannot
obtain N× its quota by spreading requests over N instances. At one instance per service neither
job is being performed — an in-process counter enforces the identical limit, and an in-process
listener registry delivers the identical event. This is removing distributed infrastructure the
topology does not use, which is why it is framed as an architecture correction rather than a
free-tier workaround.

**Consequence.** Nothing is deleted: both adapters live behind one port and one shared behavioral
contract test, so scaling past a single instance is a profile flip rather than a rewrite, and
local development keeps exercising the Redis path through Compose. The cost is real — new code on
the write path, which is precisely why it gets its own milestone and its own verification pass
instead of riding along inside M10.

---

## 2026-08-26 — Free tier for Render, with UptimeRobot warming `api` only

**Context.** M10 needed a tier decision for `api` and `ai`. Render's free tier sleeps a service
after 15 minutes idle, giving a 30–60 second cold start on the first request — poor for a link
handed to a recruiter. M9.5's T10 had already chosen UptimeRobot as the mitigation but deferred
configuration until real URLs existed.

**Decision.** Free tier for both services. UptimeRobot pings `api` only, every 5 minutes,
against `/actuator/health`. `ai` is left to sleep.

**Alternatives.**
- *Ping both services 24/7* — rejected as self-defeating: the free tier caps at 750 instance-hours
  per month across all free services, and two services kept awake continuously consume roughly
  1,460, exhausting the quota around day 15 and suspending both until the month resets. The
  mitigation would have caused a worse outage than the problem.
- *Ping both, only during demo windows* — stays inside quota but depends on remembering to toggle
  monitors before showing the link to anyone.
- *Starter tier (~$14/mo)* — no quota, no cold starts, nothing to manage. Rejected as unjustified
  monthly spend for a portfolio deployment.

**Rationale.** ~730 hours for a single warmed service sits just inside the quota. It also puts the
remaining cold start on the least-likely action: the demo organization is seeded with three months
of invoices, policies, budgets and alerts, so a visitor can explore the dashboard, expenses,
review queue and charts without ever uploading anything — and everything on that path is served by
the warm `api`. Only a visitor who chooses to upload waits for `ai` to wake.

**Consequence.** The README states the cold-start behavior plainly under Known limitations rather
than letting a stranger discover it as a hang. If the quota math changes or a second free service
is added, the warming strategy has to be recomputed — 750 hours is shared across the account, not
per service.

---

## 2026-08-26 — Policy chunks stay categorization context; enforcement is out of scope

**Context.** Manually testing the categorization pipeline (uploading a Boardroom Bistro invoice
against the seeded Client Entertainment policy) surfaced two things worth recording before
declaring the project done: (1) each policy document is small enough to embed as a single
1500-char chunk (`apps/ai/app/policy/chunking.py`), so a chunk can hold several numbered rules
at once; the categorization prompt asks the model to quote the exact chunk text that justified
its choice, but a model naturally quotes the one relevant sentence, not the whole chunk — the
verbatim-match check in `ExpensePostingService.withTrustedCitation` then (correctly) rejects
that partial quote and nulls the citation. (2) More importantly: nothing in the system reads a
policy's actual rule content (a $150-per-person meal limit, a $300 manager-approval threshold,
a 25% alcohol cap) and enforces it. Policy chunks are retrieved and handed to the LLM purely as
categorization context — "pick a category, optionally cite why" — never as a compliance check
against amounts, approvals, or thresholds.

**Decision.** Ship without policy-rule enforcement. Document the boundary explicitly (this
entry, plus a README "Known limitations" section) rather than bolt on a partial enforcement
layer before the deploy milestone.

**Why.** This is a portfolio project, not a production expense system serving a real
organization — the audience is evaluating engineering judgment and depth, not feature
completeness. Policy enforcement is a real feature (limit extraction from policy text, an
approval workflow, new schema, new UI, its own test suite), not a bug fix; scoping it in days
before a deploy would produce exactly the rushed, half-finished result this decision is meant
to avoid. The categorization safety behavior already in place — never trusting the model's
citation claim without verifying it against the retrieved chunks verbatim, silently discarding
an unverifiable one rather than failing the whole categorization — is itself a defensible,
demonstrable design point standing on its own.

**Consequence.** A category decision may carry `citation: null` even when a relevant policy
chunk was retrieved and did inform the model's choice — this is expected, not a defect, per
the citation-integrity check above. No amount is checked against a policy limit anywhere in
the code path; a demo user could post an expense that plainly violates a written policy rule
and it would go to `POSTED` or `NEEDS_REVIEW` on categorization confidence alone, never on
policy compliance. If this project resumes as more than a portfolio piece, the next step is a
dedicated milestone: chunk policies at the rule level (not the whole-document level), extract
structured limits, and add either a rule-based or a second LLM-verification enforcement pass —
planned properly with milestone-plan, not folded into deploy prep.

---

## 2026-08-25 — Demo dashboard: two categories this month, a real month-over-month comparison

**Context.** User flagged the demo dashboard's "this month" view: only one category
(Meals & Entertainment, $758) showed, and the month-over-month card read "No spend last month
to compare against." Root cause: `DemoSeedRunner.backdateTimestamps` only special-cased
Boardroom Bistro's invoices to stay at seed-run time (posting time) — every other invoice,
TechGear included, backdated to its own `document_date` (2026-03..2026-05). With nothing left
at "this month," the dashboard's `expense.created_at`-based month filter had one category and
no prior-month baseline.

**Decision.** Split `backdateTimestamps` into three groups by filename:
1. Boardroom Bistro (all) and TechGear's June invoices stay at posting time — the dashboard's
   "this month" now shows two categories instead of one.
2. One CloudHost and one QuickPrint invoice (both originally 2026-03) move to last month
   (relative to seed-run time, not their own `document_date`) — gives the month-over-month
   comparison a real prior-month total to diff against instead of zero.
3. Everything else keeps backdating to its own `document_date`, preserving the believable
   multi-month history.

Implemented as plain `UPDATE`s (documented in the method's own javadoc) — `created_at` is
`updatable = false` by design on these entities, deliberately bypassed for demo seed data only.

**Why.** User wanted the demo to look better without a) rewriting `generate_invoices.py` and
re-running the LLM extraction pipeline, or b) touching the production posting path. Extending
the existing backdate rule was the smallest change that satisfies both.

**Verified.** Compiled clean (`./mvnw -B -q compile`). Wiped the existing demo org via direct
SQL (trigger disable/enable pattern, same one `ledger_transaction`'s DELETE-trigger bug forced
earlier) so the seed would re-run from empty rather than short-circuit on the idempotency
check. Rebuilt the `api` image, brought it up with `SPRING_PROFILES_ACTIVE=demo`, waited for
`Demo seed: done`. Queried `/api/v1/dashboard/summary` directly:
- `totalsThisMonth`: $5,609.00; `totalsLastMonth`: $218.00 — real baseline, no longer zero.
- `categoryBreakdown`: Equipment & Hardware ($4,851.00) + Meals & Entertainment ($758.00) — two
  categories.
- `recentAlerts`: all three alert types present — `BUDGET_THRESHOLD` (x2, 80%/100% thresholds),
  `ANOMALY_HIGH`, `DUPLICATE_SUSPECTED`.
Restarted `api` again afterward and confirmed idempotency held: `Demo seed:
demo@ledgerly.dev already exists, skipping`. Full suite: `./mvnw -B test` → 219/219 passing,
`BUILD SUCCESS`. `infra/docker-compose.override.yml` (the temporary demo-profile override)
deleted after verification, per the standing convention of not leaving it in the tree.

---

## 2026-07-26 — Java 21 over 17 for `api`

**Context.** The local machine had Temurin 17 installed; the milestone plan hedged "21 preferred,
17 is fine — no virtual-thread dependency yet." M1 T2 needed `maven.compiler.release` set to one
value or the other before the first build, not decided by a failing build.

**Decision.** Install Eclipse Temurin 21 alongside 17, and pin `maven.compiler.release=21` in
`apps/api/pom.xml`.

**Why.** Virtual threads matter for the specific async-LLM-call problem this project already
anticipates (`api` calling `ai`, `ai` calling an LLM provider, at M4/M5) — blocking I/O over a
thread-per-request model gets expensive under that pattern, and virtual threads remove the cost of
matching thread count to concurrent request count. Spring Boot 3.5, the version already chosen for
this project, primarily targets 21. Render's containerized deploy at M10 carries its own JDK
regardless, so the local machine's prior default does not constrain the decision.

**Consequence.** `docs/versions.md` pins Java to 21.0.11+10. The M2 consequence: Testcontainers is
required for integration tests (H2 accepts SQL Postgres rejects, so H2-backed tests would pass and
production would fail) — datasource and schema work is deferred to M2 regardless of JDK version,
but the JDK choice is made now so M2 does not inherit an undecided dependency.

**Alternatives rejected.**
- *Stay on 17* — the machine's existing default, zero-install-cost. Rejected because it forecloses
  virtual threads for the M4/M5 async-LLM-call path without buying anything in return; Spring Boot
  3.5 works on 17 but is built with 21 as the primary target.

---

## 2026-07-26 — Polyglot split: Spring owns money, FastAPI owns agents

**Context.** The project has to demonstrate backend depth, fullstack ability, and AI engineering
at once. A single-language codebase makes one of the three look thin.

**Decision.** Two backend services. `api` in Java 21 / Spring Boot 3 is the system of record for
all financial state. `ai` in Python 3.12 / FastAPI runs the LangGraph agents and returns advisory
proposals only.

**Why.** Java/Spring is where enterprise fintech actually lives, and it is the stronger of the two
skill sets here. The agent ecosystem (LangGraph, evaluation tooling, multimodal SDKs) is
materially better in Python. Splitting also produces a genuine service boundary — a contract,
independent deployment, correlated logging — rather than a monolith with a folder called
`services`.

**Cost.** Two deployments, two dependency trees, a contract to keep in sync, and cross-service
debugging. Accepted; the boundary carries its own weight because it is also the trust boundary.

**Alternatives rejected.**
- *All Python/FastAPI* — faster to build, but leaves the strongest skill undemonstrated and misses
  what enterprise fintech postings actually ask for.
- *All Java with Spring AI* — clean for fintech signal, but LangGraph-class agent orchestration and
  evaluation tooling are not there yet.

---

## 2026-07-26 — The LLM is never on the write path

**Context.** Agents extract amounts, dates, and categories from documents. Those values end up in
a financial ledger.

**Decision.** `ai` returns proposals. `api` validates them deterministically — currency known,
`total == sum(lines) + tax`, date in range, amount under the org ceiling — and only then posts a
ledger entry. Anything that fails validation goes to a review queue and writes nothing.

**Why.** Extraction accuracy will never be 100%. The question is what a wrong answer costs. Behind
a validation gate it costs a review-queue item; on the write path it costs a corrupted ledger that
someone has to reconcile by hand. The gate also makes model swapping safe, since correctness does
not depend on which provider is configured.

**Consequence.** The validation layer (M4) is built before the real extraction agent (M5),
against a stub. Validation written after the model it validates tends to be shaped around the
model's bugs instead of the specification.

---

## 2026-07-26 — Money as minor units in BIGINT, never floating point

**Context.** Standard financial-software requirement, and a common failure in portfolio projects.

**Decision.** Every monetary column is `amount_minor BIGINT` plus `currency CHAR(3)`. In-memory
representation is a `Money` value object over `BigDecimal`. A CI check fails the build if any
migration introduces `float`, `real`, or `double precision`.

**Why.** `0.1 + 0.2 != 0.3` in binary floating point. In a ledger that surfaces as cent drift that
compounds across thousands of rows and cannot be reconciled after the fact. The CI grep exists
because this rule is easy to state and easy to violate six weeks later at 1am.

---

## 2026-07-26 — Double-entry bookkeeping over a flat expense table

**Context.** A simple `expenses` table with an amount column would satisfy the feature list.

**Decision.** A real double-entry ledger: `ledger_transaction` headers with balanced
`ledger_entry` rows. Entries are immutable; corrections are reversing transactions. The balance
invariant is enforced in the domain *and* by a deferred database constraint.

**Why.** Double-entry is the single clearest signal that the author understands financial systems
rather than CRUD with a currency symbol. It also makes an entire class of bug structurally
impossible — the books either balance or the transaction does not commit. Enforcing it in two
independent places means a domain-layer bug still cannot corrupt the data.

**Cost.** More schema, a chart of accounts, and a steeper learning curve than a flat table.
Accepted deliberately.

---

## 2026-07-26 — LLM provider decision deferred to M4

**Context.** Gemini Flash and Claude are both viable for multimodal invoice extraction, with
different cost and accuracy profiles.

**Decision.** Do not choose now. `ai` defines an `LlmClient` port with `complete()` and
`complete_vision()`; adapters are configuration-selected. The choice is made at M4 by running both
against ten real invoices.

**Why.** Extraction accuracy on actual documents is measurable and the deciding factor. Guessing
now trades a real measurement for a guess and gains nothing — the port is worth having regardless,
since it is also what makes the M4 stub and the M5 eval harness possible.

---

## 2026-08-25 — Bridge Render's `DATABASE_URL` in an `EnvironmentPostProcessor`, not a `@Bean`

**Context.** Render's managed Postgres exposes one `postgres://user:pass@host:port/db`
connection string. Spring's `spring.datasource.url` needs a JDBC URL, and `application.yml`
resolves `${DATABASE_URL}` directly into that property before any `@Configuration` bean runs.

**Decision.** `DatabaseUrlEnvironmentPostProcessor` (`apps/api/.../DatabaseUrlEnvironmentPostProcessor.java`)
rewrites `DATABASE_URL` into `spring.datasource.url`/`username`/`password` via
`RenderDatabaseUrl.parse()` — a pure function, tested independently — before the environment is
handed to context refresh. Registered through
`META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`, the only file
Spring Boot 3.x actually reads for this factory type; the legacy `META-INF/spring.factories`
mechanism is silently ignored for `EnvironmentPostProcessor` on Boot 3.x and will not error if
used by mistake.

**Why.** A `@Bean`/`@ConfigurationProperties` approach runs too late — `DataSourceAutoConfiguration`
already needs the JDBC-shaped URL by then. Parsing must happen at the environment-property level,
before Spring resolves `${DATABASE_URL}` into `spring.datasource.url` itself.

**Watch for.** Use `getRawUserInfo()`/`getRawQuery()`, not the decoded `getUserInfo()`/`getQuery()`
— decoding an already-decoded value corrupts any literal `%` in a password. On a malformed
`DATABASE_URL`, never chain `URISyntaxException` as the exception's cause or echo `e.getMessage()`
— both embed the full raw connection string, credentials included, into the startup stack trace.

---

## 2026-08-25 — Uploaded-document storage: Cloudflare R2 in prod, local disk everywhere else

**Context.** `LocalDiskStorage` (M4) is rooted at a directory on the container's own disk. On
Render's free/starter web-service tier that disk does not survive a restart, redeploy, or
spin-down — a stranger clicking the demo link, uploading an invoice, and coming back later
would find it gone. Three options were on the table: accept the loss and document it, pay for
a Starter-tier persistent disk (~$7/mo, and still not zero-downtime once a disk is attached),
or add a durable object-storage backend behind the existing `StorageClient` port.

**Decision.** Added `R2StorageClient`, an S3-compatible adapter for Cloudflare R2, active only
on the `prod` Spring profile (`@Profile("prod")`); `LocalDiskStorage` keeps `@Profile("!prod")`
and stays the default for local dev, tests, and CI. R2 was chosen over S3 for its free tier: 10
GB storage, free egress, no time limit or auto-expiry — $0/mo indefinitely for a portfolio
project's traffic. `R2ClientConfig` wires the `S3Client` bean with
`pathStyleAccessEnabled(true)` and `chunkedEncodingEnabled(false)` (both required — R2 rejects
the SDK's default chunked transfer encoding with a signature mismatch) and `Region.of("auto")`
per Cloudflare's own docs.

**Why.** A portfolio project's whole value is a stranger being able to click the link and see
it work; losing an uploaded document to a routine spin-down undermines exactly that. The
`StorageClient` port (added at M4 specifically anticipating this) made the swap a new
implementation with zero caller changes.

**Deviation from plan.** The milestone plan's original test criterion called for an
integration test against localstack or a live scoped bucket. Executed instead: a unit test
against a mocked `S3Client` (`R2StorageClientTest`) — chosen deliberately (asked of, and
confirmed by, the user during execution) to keep CI free of network/credential dependencies.
Trade-off: this proves request shape and error mapping, not that R2 itself behaves as the SDK
assumes; a first real deploy is the actual integration check.

**Watch for.** `StorageKeys.isValidShape()` is the one key-shape validator shared by every
`StorageClient` implementation — do not reintroduce a second copy of the UUID regex in a future
backend.

---

## 2026-08-25 — `.env.example` documents only variables `docker-compose.yml` actually reads

**Context.** The deploy-readiness plan flagged `SPRING_DATA_REDIS_HOST`,
`SPRING_DATA_REDIS_PORT`, and `AI_RATE_LIMIT_REDIS_URL` as missing from `.env.example`.
Investigating before adding them found they are not `${VAR}` interpolations in
`docker-compose.yml` at all — `SPRING_DATA_REDIS_HOST: redis` and
`AI_RATE_LIMIT_REDIS_URL: redis://redis:6379/0` are bare literals, and
`SPRING_DATA_REDIS_PORT` isn't referenced anywhere in the file. Setting any of them in `.env`
would silently do nothing.

**Decision.** Did not add the three Redis variables. Ran the actual diff instead (every
`${VAR}` in `docker-compose.yml` vs. every key in `.env.example`) and found the real gap:
`API_URL`, `LOADTEST_DOCUMENT_QUOTA`, `LOADTEST_AI_QUOTA`. Added those, and added
`scripts/check-env-example.sh` (wired into CI as its own job) so this diff is enforced going
forward instead of re-derived by hand.

**Why.** Documenting a variable the compose file never reads is worse than not documenting
it — it invites a future operator to "fix" a problem in `.env` that the literal in
`docker-compose.yml` makes unfixable there, and to waste time debugging why it had no effect.

---

## 2026-08-25 — Embedding provider is Voyage AI, not the OpenCode Go gateway

**Context.** `AI_EMBEDDING_PROVIDER=litellm` was configured against the same OpenCode Go
gateway (`AI_LLM_API_BASE`) used for chat completion (extraction/categorization). Recording
T5.3's policy-embedding fixture was the first real, non-`fake`-provider exercise of the
embedding path — it had never actually been run against a live provider before.

**Finding.** The OpenCode Go gateway only proxies chat completion. Every embedding-capable
model tried through it (`anthropic/qwen3.7-plus`, `openai/text-embedding-3-small`,
`voyage/voyage-3`, `cohere/embed-english-v3.0`) either 404s (gateway has no route) or fails
with `litellm.BadRequestError: Unmapped LLM provider for this endpoint` — Anthropic itself has
no native embedding API, so there is no model this gateway could route an embedding call to
regardless of name. **Policy embedding has never worked end-to-end before this session.**

**Decision.** Switched the embedding provider to Voyage AI (`voyage/voyage-3`, called
directly — no `api_base` override), free tier (50M tokens, no credit card). 1024-dimension
vectors; `policy_chunk.embedding` has no fixed dimension in its column type, so this needed no
schema change. Local dev's `infra/docker-compose.override.yml` (gitignored) sets
`AI_EMBEDDING_MODEL`/`AI_EMBEDDING_API_BASE`/`AI_EMBEDDING_DIMENSIONS`; the real API key lives
in `.env`, not in git.

**Consequence for M10.** `docs/milestones.md` M10's Render blueprint must set
`AI_EMBEDDING_MODEL=voyage/voyage-3` and a real `AI_EMBEDDING_API_KEY` explicitly — the
`app/config.py` default (`anthropic/qwen3.7-plus` through the OpenCode Go gateway) does not
work for embeddings and must not be relied on for this one setting.

---

## 2026-08-25 — Two real bugs found while recording T5.3, filed for a later fix

Found during manual `curl` testing against the running stack, not part of T5's own scope —
noting here so they aren't lost, not fixing them mid-seed-recording:

1. **`IdempotencyFilter` maps a missing `Idempotency-Key` header to `401`, not `400`.** The
   filter itself calls `response.sendError(HttpStatus.BAD_REQUEST.value(), ...)`
   (`IdempotencyFilter.java:72`), but every `POST /api/v1/policies` request without that header
   observably returned `401 Unauthorized` with an empty body, not `400`. Reproduced repeatedly
   against a known-valid bearer token. Root cause not yet isolated — worth its own investigation
   (likely something intercepting/overriding `sendError` before it reaches the client, possibly
   interacting with `HttpStatusEntryPoint` from `SecurityConfig`).
2. **Policy-upload rate limit (`ledgerly.rate-limit.policy-upload.max-requests:2` per 60s) is
   easy to hit by accident** during any manual/scripted multi-file upload session (T5.3 hit it
   uploading a 3rd file within the same minute). Not a bug — correct behavior for its stated
   purpose — but worth remembering for any future script or manual session doing more than 2
   policy uploads per minute: budget retry/backoff for it rather than being surprised by a 429.
3. **Parallel `POST /api/v1/documents` uploads occasionally produce a permanent (non-retried)
   `ExtractionRequestRejectedException`** even though the same document succeeds when retried
   sequentially. Reproduced during T5.4 (4 of 21 concurrently-uploaded invoices failed this
   way; sequential re-upload of the same, unmodified files succeeded every time). Root cause
   not isolated — `HttpExtractionClient`'s catch-all `RestClientException` branch treats this
   as a permanent 4xx and never retries, but no corresponding log line appears on the `ai`
   side for the failed request, suggesting the request may not be reaching `ai` at all under
   concurrent load (client-side connection pool exhaustion in
   `SimpleClientHttpRequestFactory`, not a real 4xx from the server, is the leading
   hypothesis). Worth its own investigation before relying on concurrent multi-document upload
   in any other context (e.g. a future load test or bulk-import feature).

---

## 2026-08-25 — `LOW_CONFIDENCE` dropped from T5's demo alert coverage

**Context.** T5.5's seed runner was planned to make all four alert types
(`BUDGET_THRESHOLD`, `ANOMALY_HIGH`, `LOW_CONFIDENCE`, `DUPLICATE_SUSPECTED`) fire at least
once in the demo org, using T5.2's deliberately sparse/ambiguous
`skyline_flight_april_sparse.pdf` invoice to trigger `LOW_CONFIDENCE` (categorization
confidence below the `0.7` threshold).

**Finding.** Recorded against the real categorization LLM (T5.4), every content variant tried
— a fully vendor-less invoice, one with no category-indicating line items at all, one with
policy-chunk retrieval deliberately given nothing relevant to match — still returned ≥0.9
confidence, landing on `Other Operating Expenses` as a confident fallback rather than an
uncertain one. The model does not appear to express genuine uncertainty as a low confidence
score for this input shape; it commits to the closest category it can find.

**Decision.** Dropped `LOW_CONFIDENCE` from the demo's alert coverage. The alternative —
temporarily raising `CATEGORIZATION_CONFIDENCE_THRESHOLD` during the recording session so a
real 0.9 confidence reads as "low" for this one fixture — was considered and rejected: it
would record a confidence value that is not actually below the production threshold, which
misrepresents what the real system did. T5.5 now targets three alert types, and the Review
queue is empty in the seeded demo rather than holding 2–3 pending items.

**Consequence.** The demo cannot show the Review/approve-low-confidence-expense flow with
real seeded data. If that flow needs to be demonstrable later, the honest way to get there is
either finding a genuinely confidence-suppressing input shape (not yet found) or a manual,
clearly-labeled demo step that doesn't claim to be organic seed data.

---

## 2026-08-25 — Bug found: `check_ledger_transaction_balance()` breaks on a real `ledger_transaction` DELETE

**Context.** Cleaning up T5.3/T5.4's temporary recording org (`DELETE FROM ledger_transaction
WHERE ...`) failed every time with `ledger_transaction <id> has no entries`, even inside a
single transaction that also deleted the transaction's `ledger_entry` rows first.

**Root cause.** `check_ledger_transaction_balance()` (`V2__deferred_balance_constraint.sql`)
opens with:
```sql
IF TG_TABLE_NAME = 'ledger_transaction' THEN
    affected_transaction_id := NEW.id;
```
On a `ledger_transaction` `DELETE`, there is no `NEW` row — `NEW.id` is `NULL`. Every
downstream `SELECT ... WHERE id = affected_transaction_id` then matches nothing,
`entry_count` comes back `0`, and the function raises "has no entries" regardless of the
actual data. **This trigger has never been exercised by a real `ledger_transaction` DELETE
before** — production code only ever inserts (reversing entries correct a mistake; nothing
deletes a transaction), so the bug has sat latent since M2.

**Not fixed here** — out of T5's scope, a real code fix belongs in its own task. Worked
around for this one cleanup with `ALTER TABLE ledger_transaction DISABLE TRIGGER ALL` /
`ledger_entry DISABLE TRIGGER ALL` around the delete, then re-enabled immediately after.

**Fix, when someone picks this up:** use `OLD.id` when `TG_OP = 'DELETE'` and
`TG_TABLE_NAME = 'ledger_transaction'`, mirroring the `OLD.transaction_id` branch already
below it for `ledger_entry` deletes.

---

## 2026-08-25 — T5.5: `demo` profile seed replays fixtures through the real service layer

**Context.** T5.1–T5.4 produced generator scripts, PDFs, and two recorded fixtures
(`policy-chunks.json`, `invoice-extractions.json`). T5.5 needed to turn those into an
idempotent, LLM-free `demo` Spring profile that leaves the demo org in the same shape a real
upload session would.

**Decision.** `DemoSeedRunner` (`com.ledgerly.api.demo`, `@Profile("demo")`,
`ApplicationRunner`) drives the *real* transactional services directly, passing in the
recorded fixture data instead of calling `ai`:
- Policies: `PolicyUploadTransactions.createPendingDocument` → `markProcessing` →
  `recordEmbedded`, fed the fixture's chunks/embeddings via a reconstructed
  `EmbedPolicyResponse` instead of a real `PolicyEmbeddingClient` call.
- Invoices: `DocumentUploadService.upload` (real bytes to real storage) →
  `DocumentStatusTransitions.markProcessing`/`recordOutcome` (using `ProposalMapper.parse` +
  `ExtractionProposalValidator.validate` against the fixture's own recorded extraction JSON,
  both fully deterministic — no `ai` call) → `ExpensePostingTransactions.recordPosted`
  (fixture's category/confidence/citation, again no `ai` call).
- Budget: created via the real `BudgetService.create`.

PDFs live at `scripts/demo_seed/pdfs/` (generated by T5.1/T5.2) and are pulled into
`apps/api`'s jar via a `pom.xml` resource entry plus a matching `COPY` in `Dockerfile` — one
source of the bytes, not a hand-copied second tree.

**Why not just call `ExpensePostingService.categorizeAndPost` / `PolicyUploadService.upload`
directly?** Those *are* the real, LLM-calling entry points — using them would defeat the
"record once, replay forever" point of T5.3/T5.4 and re-introduce cost/non-determinism on
every seed run. `*Transactions` (the layer directly below, doing only persistence) was the
right seam: real domain logic and real side effects (ledger balance checks, budget threshold
evaluation, duplicate detection), zero network calls.

**Two real findings from getting this working:**
1. `BudgetThresholdEvaluator` keys the budget period off the *posting instant*
   (`Instant.now()` at seed time), not the expense's own `document_date`. The budget this
   seed creates for `Meals & Entertainment` is therefore period `YearMonth.now()`, not a
   fixed past month matching the invoices' own (2026-06) dates — otherwise
   `BUDGET_THRESHOLD` would silently never fire.
2. `UploadRateLimiter`'s default quotas (`document-upload: 10/60s`, `policy-upload: 2/60s`)
   exist to bound a real user's paid-AI-service calls from a browser and are far too tight
   for one seed pass uploading 21 invoices + 3 policies in a few seconds. Added
   `application-demo.yml` raising both for the `demo` profile only — production's real
   limits are untouched.

**One deliberate real network call remains:** `ExpensePostingTransactions.recordPosted`
publishes `ExpensePostedEvent`, which `AnomalyAlertListener` picks up `AFTER_COMMIT` and
calls `ai`'s `/anomaly` endpoint for a qualitative explanation on any HIGH-risk expense — a
handful of times per seed run. Accepted deliberately (see the T5.4 entry above on the same
question for policy embedding): this is what production does on every real anomalous
posting too, so faking it here would put words in the demo alert that the real system never
said. In a test environment with `ai` unreachable, this degrades to no `ANOMALY_HIGH` alert
rather than failing the seed — `AnomalyAlertListener` catches `RuntimeException` and only
warns.

**Unrelated flaky ITs found while running `mvn verify` here** (not caused by this change,
both pass in isolation): `ExpensePostingPipelineIT` and `DocumentStatusPipelineIT` each
failed once under the full parallel IT run and passed cleanly when run alone — a pre-existing
test-isolation issue in the IT suite, not something this task touched. Not investigated
further: CI only runs `mvnw test` (unit tests), never `mvn verify`, so these don't gate
anything today, but worth knowing about before ever adding `mvn verify` to CI.

**Post-review fixes (same task, after independent verification):**
1. `DocumentQueuePoller` (`@Scheduled`, ticks every `DOCUMENT_QUEUE_INTERVAL_SECONDS`, default
   5s) starts during context refresh, before `DemoSeedRunner`'s `ApplicationRunner` phase runs
   — a real race window existed between `DocumentUploadService.upload()` leaving a document
   `PENDING` and `DemoSeedRunner`'s own `markProcessing()` call a line later. A poll tick
   landing in that window would let the poller win the `PENDING → PROCESSING` claim and
   dispatch a real `ai` extraction, which the status-transition guard then turns into a hard
   `IllegalDocumentTransitionException` aborting the seed (not silent corruption — the two
   writers can't both proceed — but still an avoidable failure mode). Fixed by adding
   `@Profile("!demo")` to `DocumentQueuePoller` — the demo profile deliberately replaces the
   extraction pipeline with fixture replay, so no competing dispatcher should run at all.
2. The idempotency guard checked the *first* thing the seed creates (the demo user's
   existence), so a crash mid-run (e.g. after registering the user but before the last invoice
   posts) would permanently read as "already exists" on every future boot over a genuinely
   incomplete demo org. Changed to a completion sentinel:
   `expenseRepository.countByOrganizationId(...) >= fixtures.readInvoices().size()` (added
   `ExpenseRepository.countByOrganizationId`), checked against the *last* thing `run()` does.
   A partial prior run now reuses the existing user/org and resumes rather than silently
   latching as done — reseeding invoices unconditionally on resume is a known remaining gap
   (would re-upload already-posted invoices rather than skipping them), accepted for a
   disposable demo database rather than adding resume-tracking machinery.

---

## 2026-08-25 — Voyage embedding defaults moved into `apps/ai/app/config.py` permanently

**Context.** T5.3's embedding-provider fix (Voyage AI, see the 2026-08-25 entry above) was
only ever applied via a temporary `infra/docker-compose.override.yml` used during fixture
recording. That file was deleted once T5.5 finished. The real `ai` image's baked-in default
(`embedding_model = "anthropic/qwen3.7-plus"` through the OpenCode Go gateway) was never
actually changed — so any policy upload against a freshly rebuilt/restarted `ai` container
failed with "Policy embedding service call failed", exactly the bug T5.3 found and fixed only
for the recording session, not for real usage. Caught when manually re-testing the seeded
demo account's Policies page after `ai` was rebuilt post-T5.5.

**Decision.** Moved the fix into `apps/ai/app/config.py`'s actual defaults:
`embedding_model = "voyage/voyage-3"`, `embedding_api_base = None` (was the OpenCode Go URL —
`None`, not `""`, so LiteLLM's own default routing applies), `embedding_dimensions = 1024`
(Voyage's real output size, was 1536). `docker-compose.yml`'s existing "don't hardcode
provider defaults here, `app/config.py` is the one source of truth" comment (from the Q1
provider-drift lesson) is exactly right — this fix belongs in code, not in a compose override.

**Why this was missed the first time.** T5.3 solved "make the recording session work" and
treated that as the whole problem; it should have asked "what does a real deployment need"
from the start. The override file's very existence was the tell — a fix that only lives in a
gitignored, manually-created file was never going to survive a normal rebuild.

---

## 2026-08-25 — T6: minimal error tracking (Sentry) in `api`, `ai`, `web`

**Context.** No prod exception visibility existed anywhere — every failure had to be found by
grepping structured logs. T6 wires Sentry's free tier (5,000 events/mo) into all three
services, PII-safe (same bar as M9's structured-logging review).

**Decision.**
- `api`: `sentry-spring-boot-starter-jakarta:8.53.0` (the Boot 3.x/jakarta-namespace build —
  the plain `sentry-spring-boot-starter` targets Boot 2/javax and silently fails to
  autoconfigure). `sentry.send-default-pii: false`, `sentry.max-request-body-size: none`
  (Java SDK's enum value — Python's own SDK uses the string `"never"` for the same setting,
  the two are not interchangeable, see the finding below).
- `ai`: `sentry-sdk[fastapi]==2.62.0`, initialized in `main.py` only when `AI_SENTRY_DSN` is
  set, `send_default_pii=False`, `max_request_body_size="never"`.
- `web`: `@sentry/nextjs@^10.70.0`, App Router setup —
  `src/instrumentation-client.ts` (browser), `src/sentry.server.config.ts` (Node runtime),
  `src/sentry.edge.config.ts` (edge runtime), `src/instrumentation.ts` (registers the
  server/edge configs and exports `onRequestError`), `next.config.ts` wrapped in
  `withSentryConfig`. DSN is `NEXT_PUBLIC_SENTRY_DSN` — public and inlined into the client
  bundle at *build* time, so `apps/web/Dockerfile`'s build stage needed new `ARG`s
  (`docker-compose.yml`'s `web.build.args`) in addition to the usual runtime
  `environment:` entries; a `NEXT_PUBLIC_*` var set only at container-runtime never reaches
  browser code.
- No session replay, no performance tracing, no profiling on any of the three — error capture
  only, to stay inside the free tier's event budget and avoid capturing on-screen invoice
  data via replay.

**Real finding: the Spring `@RestControllerAdvice` swallows every exception before Sentry's
auto-instrumentation ever sees one.** `ApiExceptionHandler`'s `@ExceptionHandler(Exception.class)`
catches everything the application throws and returns its own `ProblemDetail` — by design
(never leak internals to a client), but that also means no exception ever reaches the servlet
layer unhandled, which is the only layer Sentry's Spring Boot starter instruments
automatically. Manually tested with a live container: a real 500 (`GET
/api/v1/expenses/not-a-valid-uuid`) produced **zero** events in the `ledgerly-api` Sentry
project until `Sentry.captureException(exception)` was added inside `handleUnexpected()`
itself. `ai`'s and `web`'s equivalents don't have this problem — `ai`'s only
`@app.exception_handler`-style catches are for expected 4xx outcomes (deliberately not sent
to Sentry), and `web`'s global `window.onerror`/React error boundary path is exactly what
`@sentry/nextjs` auto-instruments.

**Verified end-to-end**, not just "should work": a real 500 was triggered against the
rebuilt `api` container and confirmed captured; `ai`'s wiring was proven with a manual
`sentry_sdk.capture_exception()` call returning a real event id; `web`'s was proven by
triggering a real unhandled browser error and observing a `200` POST to
`https://o4511972980555776.ingest.de.sentry.io/.../envelope/` in the network tab. All three
projects showed the corresponding event in the Sentry dashboard.

---

## 2026-08-25 — T7: secrets audit

**Git history.** Searched `git log --all -p` for the five named env vars
(`JWT_SECRET`, `AI_SERVICE_TOKEN`, `AI_LLM_API_KEY`, `AI_EMBEDDING_API_KEY`,
`POSTGRES_PASSWORD`) and independently for exact fragments of every real secret value
currently in the local `.env` (JWT signing secret, AI service token, LLM/embedding API
keys, Postgres password, Sentry DSNs). Every hit outside `.env.example` was a placeholder
(`generate-a-long-random-value`, `fake`, `change-me-locally`); zero occurrences of any real
value. `.env` itself has never been committed (`git log --all -- .env` is empty) and is
gitignored.

**Real finding, fixed as part of this audit.** Seven stray log files at the repo root
(`m93-t6-run.log`, `t6-api-verify.log`, and five siblings — leftover manual-verification
output from earlier sessions) were never committed (`*.log` is gitignored) but
`t6-api-verify.log` contained four real JWT access tokens in plaintext on disk. Deleted all
seven. No git exposure occurred, but a 485KB file of live tokens sitting in a repo
working tree is exactly the kind of thing that gets accidentally `git add -A`'d later —
worth the habit of not letting verification scratch output linger past the session that
produced it.

**Trivy secret scanning added to CI**, not just a one-off manual check: a new
`scan for committed secrets` step in the existing `dependency-security` job (`.github/workflows/ci.yml`),
same `aquasecurity/trivy-action`, `scanners: secret`, `TRIVY_OFFLINE_SCAN: "true"` (reuses
the `vuln` step's already-warmed `~/.m2` cache in the same job rather than re-hitting Maven
Central and risking the same 429 that step's own warm-cache step exists to avoid). Verified
locally with the same Trivy image/version against the cleaned repo: exit 0, zero secrets
found.

**Platform secret store mapping (for M10's `render.yaml`/Vercel setup — written down now so
M10 doesn't have to re-derive it):**

| Variable | Consumed by | Platform store |
|---|---|---|
| `JWT_SECRET` | `api` | Render env var, `sync: false` in `render.yaml` |
| `AI_SERVICE_TOKEN` | `api` + `ai` (shared) | Render env var, `sync: false` |
| `AI_LLM_API_KEY` | `ai` | Render env var, `sync: false` |
| `AI_EMBEDDING_API_KEY` | `ai` | Render env var, `sync: false` (Voyage AI key — see the embedding-provider entries above; NOT the same value as `AI_LLM_API_KEY`) |
| `POSTGRES_PASSWORD` | `api` (via `DATABASE_URL`) | Render manages this directly once the database is provisioned via blueprint — not a value this repo ever sets by hand |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | `api` (`prod` profile only) | Render env var, `sync: false` |
| `SENTRY_DSN` (api) / `AI_SENTRY_DSN` (ai) | `api` / `ai` | Render env var — not secret-sensitive (a DSN is a write-only ingest endpoint) but still not hardcoded into `render.yaml` |
| `NEXT_PUBLIC_SENTRY_DSN` | `web` | Vercel env var, **not** `sync: false` — it's a public, build-time-inlined value by design (see the T6 entry above) |

**Not yet verifiable:** `render.yaml` doesn't exist yet (M10 writes it) — the "no `sync: false`
value filled with a literal secret" half of T7's test criterion has nothing to check against
until then. Re-run this check as part of M10 once the blueprint is written, not skipped here.

---

## 2026-08-25 — Demo seed backdates invoice timestamps so the org looks ~3 months active

**Context.** After T5.5 shipped, manual review of the seeded demo account found every
document/expense showing the same `created_at` (the moment the seed ran) in the web UI's
expense list, even though each invoice's own `document_date` (T5.2's fixed 2026-03..2026-06
manifest) was already spread across three months — `expense-row.tsx` displays
`expense.createdAt`, not `documentDate`, so the spread was invisible to a viewer. Not the
demo-authenticity goal T5 set out to hit.

**Decision.** `DemoSeedRunner.backdateTimestamps()` runs a plain `JdbcTemplate` `UPDATE`
immediately after each invoice's `ExpensePostingTransactions.recordPosted` call, setting
`document.created_at`/`updated_at`, `document_activity.created_at`, `ledger_transaction.posted_at`/`created_at`,
`ledger_entry.created_at`, and `expense.created_at` to the invoice's own `document_date`
(10:00 UTC). Chose a raw UPDATE over changing `recordPosted`'s signature to accept an
injectable `postedAt`: every entity's `created_at` is `updatable = false` by design (audit
history shouldn't be editable through the normal write path) and none of `api`'s real
production code needed this capability — extending a production method's signature for a
demo-only need would be the wrong trade. The **Boardroom Bistro invoices are excluded**
(matched by filename prefix) — their `document_date` is 2026-06, chosen to fall inside
`seedBudget`'s own `YearMonth.now()` period; backdating them would move them out of the
budget's period and silently stop `BUDGET_THRESHOLD` from firing.

**Known limitation, accepted deliberately.** The manifest's dates (2026-03..2026-06) are
absolute, not relative to when the seed actually runs — chosen when T5.2 was built, this
reads as "the last ~3 months" right now (2026-08) but will look stale or wrong if this seed
is ever run much later (e.g. 2027). Fixing this properly means making
`scripts/demo_seed/generate_invoices.py` compute dates relative to generation time and
re-recording T5.3/T5.4's fixtures against the real LLM/embedding calls again — real work,
not done here. Revisit if the demo is still in use materially later than originally
intended.

**Verified:** rebuilt `api`, wiped the demo org, reseeded — `document.created_at` now spans
2026-03-01 through 2026-06-25 across the 17 non-Boardroom invoices; Boardroom's 4 stayed at
the actual seed run's real timestamp (today); all three alert types
(`BUDGET_THRESHOLD`, `ANOMALY_HIGH`, `DUPLICATE_SUSPECTED`) still fired; a restart logged
"already exists, skipping" with document/expense counts unchanged (idempotency intact).
`DemoSeedRunnerIT`/`DemoSeedRunnerIdempotencyIT` still pass — their assertions were never
date-dependent.

---

## 2026-08-25 — T8: log verbosity confirmed `INFO`; real finding — Sentry's log integrations bypass M9's PII redaction

**Log verbosity.** `api` has no explicit `logging.level.*` anywhere across
`application*.yml` — Spring Boot's own default (`INFO`) applies in every profile, `DEBUG` is
never turned on. `ai`'s `observability.py` hardcodes `root.setLevel(logging.INFO)` with no
env-var or profile path to `DEBUG`. Nothing to fix here; this half of T8 was already true
before T6 existed.

**Real finding.** Sentry's default integrations read raw log records *before* any
application-level formatting or redaction runs, on all three services:
- `ai`: `sentry_sdk`'s `LoggingIntegration` is a default integration — `INFO`+ becomes a
  breadcrumb, `ERROR`+ becomes its own separate event, both built directly from the stdlib
  `LogRecord.getMessage()`. `observability.py`'s `_redact()` (the M9 PII-scrubbing layer)
  only runs inside `JsonFormatter.format()`, which is the path to stdout — Sentry's handler
  attaches to the same logger *before* formatting and never sees the redacted string. A
  `logger.error("...filename=... content=...")` call would have reached Sentry unredacted
  even though the console/log-aggregator output was clean.
- `web`: `@sentry/nextjs`'s `consoleIntegration` is a default integration — every
  `console.*` call becomes a breadcrumb attached to whatever error fires next. This codebase
  currently makes zero `console.*` calls, so no live leak, but the risk is silent and
  wouldn't show up in a code review of the log call itself.
- `api`: checked and found **not** at risk — `sentry-spring-boot-starter-jakarta` only pulls
  in `sentry-spring-jakarta` and `sentry-reactor` (`mvn dependency:tree`), not
  `sentry-logback`, so Sentry's Logback `SentryAppender` auto-configuration (which requires
  both `logback` and `sentry-logback` on the classpath) never activates. No fix needed here.

**Fix.** `ai`'s `main.py` passes an explicit `LoggingIntegration(level=None,
event_level=None, sentry_logs_level=None)` to `sentry_sdk.init()`, disabling breadcrumbs,
events, and Sentry's separate "Logs" product entirely for this integration. `web`'s
`instrumentation-client.ts` filters `consoleIntegration` out of
`Sentry.getDefaultIntegrations()`. Real exception capture is unaffected in both — `ai`'s own
`Sentry.captureException`/FastAPI's automatic exception capture, and `web`'s global
error/`onRequestError` hooks, don't go through either integration.

**Verified, not just configured:** in `ai`, a `logger.error(...)` call containing
`filename=secret_invoice.pdf content=SENSITIVE_DATA` was confirmed to reach only stdout, not
Sentry; a real `sentry_sdk.capture_exception()` call immediately after, in the same session,
still produced a real event id — proving the fix disables only the log-to-breadcrumb path,
not exception capture. In `web`, `console.error.toString()` was confirmed to still return
native code (unpatched — the integration never wrapped it), and a real unhandled browser
error was confirmed to still reach Sentry (`200` POST to the ingest envelope endpoint,
observed in the network tab).

---

## 2026-08-25 — T9: SEO/meta pass, and a real finding — the OG image was unreachable

**Decision.** `layout.tsx` gets a real `title`/`description` (previously
"Ledgerly service health dashboard" — a leftover from M1's health-check page, not a
description of the product) and a `metadataBase` resolved from a new `NEXT_PUBLIC_SITE_URL`
env var (defaults to `http://localhost:3000`; set to the real Vercel domain once deployed).
`app/opengraph-image.tsx` uses `next/og`'s `ImageResponse` to generate a 1200×630 PNG at
build time (Next.js's file-convention route, not a static asset) — no external font load, no
image asset to keep in sync with a redesign; the copy comes straight from `layout.tsx`'s own
`alt`.

**Real finding.** `proxy.ts`'s auth guard treats every path not explicitly listed in
`PUBLIC_PATHS` as protected — `/opengraph-image` isn't a page a signed-out visitor navigates
to, it's the image a social-media crawler (Facebook/LinkedIn/Twitter/Slack unfurl bots)
fetches unauthenticated the moment a link is shared, and crawlers don't carry the session
cookie. Before excluding it from the middleware's `matcher`, a direct fetch of
`/opengraph-image` returned a `307` redirect to `/login` instead of the PNG — every shared
Ledgerly link would have rendered a blank/broken preview card everywhere it was pasted.
Fixed by adding `opengraph-image` to the matcher's existing negative-lookahead exclusion
list, alongside `api`/`_next/static`/`_next/image`/`favicon.ico`.

**Verified against the real container**, not just the dev server: rebuilt and restarted
`web`, then `curl`'d the running container directly — `<title>Ledgerly</title>`,
`<meta property="og:image" content="http://localhost:3000/opengraph-image?...">` present in
`/login`'s HTML, and a direct `curl -o /dev/null -w "%{http_code}" localhost:3000/opengraph-image`
returned `200` (not the earlier `307`) with a real 1200×630 PNG body. All 212 web unit tests
and the 19-test `proxy.test.ts` suite pass unchanged — the matcher fix only widened an
exclusion list an existing test suite already exercises the shape of.

---

## 2026-08-25 — T10: free-tier uptime — UptimeRobot, configured in M10 not here

**Context.** Render's free web services spin down after 15 minutes idle; the first request
after that takes roughly a minute to wake the container. A stranger clicking a shared demo
link during that window waits, cold, for no visible reason.

**Decision.** Keep `api` and `ai` warm with UptimeRobot's free tier (up to 50 monitors,
5-minute check interval, no credit card) pinging each service's health endpoint
(`GET /actuator/health` for `api`, `GET /health` for `ai`) continuously. Rejected the
alternative (accept the cold start, document it in the README) — a demo link is meant to be
clicked on impulse by someone reviewing a portfolio; a full minute of nothing before anything
renders reads as "broken," not "warming up," to a visitor with no context on why.

**Why not configured now.** UptimeRobot needs a real URL to ping, and `api`/`ai` don't have
one yet — M10 (the actual Render deploy) hasn't happened, everything so far has run against
`localhost`. Setting this up is mechanically an M10 step: once `render.yaml` is written and
the services have real `onrender.com` (or custom) URLs, add two UptimeRobot HTTP monitors
against their health endpoints. Recorded here per T10's own scope (a *decision*, not
necessarily the automation) so M10 doesn't have to re-derive which service to pick or why.

**Two things worth remembering when M10 sets this up:**
1. A pinger keeping `api`/`ai` warm does **not** need to also ping `web` — Vercel's hosting
   model doesn't cold-start the way Render's free web services do, so there's nothing there
   to keep warm.
2. UptimeRobot's own free-tier check interval (5 minutes) is comfortably inside Render's
   15-minute spin-down window, so no plan upgrade is needed for this to actually work.

---

## 2026-08-26 — Post-T9 follow-up: real favicon, and the same auth-gate bug caught again

**Context.** `apps/web/src/app/favicon.ico` was `create-next-app`'s stock placeholder
(committed on the very first scaffolding commit) — never Ledgerly's own mark. Noticed after
T9's OG-image work, not part of T9's original scope.

**Decision.** Replaced it with `app/icon.tsx` — the same `next/og` `ImageResponse` approach
as `opengraph-image.tsx`, generating a 32×32 PNG with the identical brand color (`#1a2b6b`)
and an "L" mark, rather than committing a second static asset that could drift from the OG
image's look. Deleted `favicon.ico` outright rather than keeping both — Next.js's file-based
icon conventions don't need it once `icon.tsx` exists, and two icon sources is a way for them
to quietly disagree later.

**Same bug as T9, caught before it shipped this time.** `proxy.ts`'s matcher treats
everything not explicitly excluded as protected — `/icon` would have hit the identical
307-to-login redirect `/opengraph-image` did in T9, except here the failure mode is a
browser tab silently failing to load its favicon rather than a visible broken link preview.
Added `icon` to the same matcher exclusion list this time before it ever reached a
verification step. Worth noting as a pattern: any future `app/`-root file-convention route
(manifest.json, robots.txt generated via code, etc.) needs the same exclusion, on the same
reasoning — anything a browser or crawler fetches unauthenticated as infrastructure, not
content, belongs in that list.

**Verified:** rebuilt the real `web` container, `curl -o /dev/null -w "%{http_code}"
localhost:3000/icon` returned `200` with a valid 32×32 PNG body. Full web suite (212 unit
tests, `proxy.test.ts`'s 19) and lint pass unchanged.
