# Ledgerly

AI-driven corporate expense ledger. Upload an invoice, an agent extracts and categorizes it,
the system posts it to a double-entry ledger, and a budget guard flags anomalies before they
become surprises.

> **Status:** planning complete, implementation not started. See [docs/milestones.md](docs/milestones.md).

## Why this exists

Expense tracking tools either force manual data entry or hand back an unstructured blob of OCR
text. Ledgerly closes the loop: the document goes in, a correct and auditable accounting entry
comes out, and the system tells you when a spend pattern breaks from the norm.

The engineering constraints are the ones real financial software lives under — exact monetary
arithmetic, idempotent write paths, an immutable audit trail, and balanced double-entry
bookkeeping that cannot silently drift.

## Architecture

```
                        ┌──────────────────────────────┐
                        │  web — Next.js + TypeScript  │
                        │  dashboard, upload, agent UI │
                        └───────────────┬──────────────┘
                                        │ REST + SSE
                        ┌───────────────▼──────────────┐
                        │  api — Spring Boot 3 / Java  │
                        │  ledger · auth · idempotency │        ┌──────────────┐
                        │  audit trail · budgets       ├────────┤  PostgreSQL  │
                        └───────────────┬──────────────┘        │  + pgvector  │
                                        │ REST (internal)       └──────────────┘
                        ┌───────────────▼──────────────┐               ▲
                        │  ai — FastAPI + LangGraph    ├───────────────┘
                        │  extraction · categorization │
                        │  anomaly · policy RAG        │
                        └───────────────┬──────────────┘
                                        │
                                  ┌─────▼─────┐
                                  │    LLM    │  (provider chosen behind
                                  └───────────┘   an LlmClient port)
```

`api` owns all money and all writes. `ai` is stateless with respect to the ledger — it reads
documents and returns structured proposals, never posts entries itself. That boundary is
deliberate: an LLM must not be on the write path of a financial system.

Full detail: [docs/architecture.md](docs/architecture.md).

## Stack

| Layer | Technology |
|---|---|
| Web | Next.js (App Router), TypeScript, Tailwind, shadcn/ui |
| API | Java 21 (17 works), Spring Boot 3, Spring Security, Spring Data JPA |
| Data | PostgreSQL 16 + pgvector, Flyway migrations |
| AI | Python 3.12, FastAPI, LangGraph, pgvector retrieval |
| Infra | Docker Compose (local), GitHub Actions CI, Render (api/ai/db) + Vercel (web) |

## Repository layout

```
ledgerly/
├── apps/
│   ├── api/          Spring Boot service — ledger, auth, budgets
│   ├── ai/           FastAPI service — agents
│   └── web/          Next.js frontend
├── infra/
│   └── docker-compose.yml
├── docs/
│   ├── architecture.md
│   ├── milestones.md
│   └── decisions.md
└── .github/workflows/
```

## Getting started

Not yet runnable. The first milestone (M1) delivers `docker compose up` with three health
endpoints responding. Until then, read the docs.

## License

MIT
