# Decisions

Newest entry on top. Append-only — a superseded decision stays, with `supersedes:` on the entry
that replaced it.

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
