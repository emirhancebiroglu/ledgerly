# Contracts

The interface between `api` and `ai`, as JSON Schema. **This directory is the single source of
truth.** Neither service hand-copies a field list from the other — `api` loads these files from the
repository root at test time, and `ai` loads the same files. `api` also embeds
`extraction-proposal.schema.json` from this directory into its executable artifact and validates
every raw extraction response against it at runtime before typed binding or ledger posting. A change
here that breaks either side fails that side's contract test.

| File | Meaning |
|---|---|
| `extract-request.schema.json` | The `metadata` part of `POST /extract` on `ai`. Bytes travel as the multipart `file` part. |
| `extraction-proposal.schema.json` | What `ai` returns. Advisory only — `api` validates raw JSON against this exact embedded schema, then applies deterministic posting rules. |
| `embed-policy-request.schema.json` | The `metadata` part of `POST /embed-policy` on `ai`. Bytes travel as the multipart `file` part. |
| `embed-policy-response.schema.json` | What `ai` returns — a policy document split into chunks with embeddings. `api` persists these as `policy_chunk` rows. |
| `embed-query-request.schema.json` | The full body of `POST /embed-query` on `ai` — plain JSON, text only. |
| `embed-query-response.schema.json` | A single embedding vector for `api` to use in a pgvector nearest-neighbor search. |
| `categorize-request.schema.json` | The full body of `POST /categorize` on `ai` — extracted fields, org taxonomy, and `api`'s already-retrieved policy chunks. |
| `categorize-response.schema.json` | What `ai` returns — category, confidence, and the citation that justified it. Advisory only. |
| `anomaly-request.schema.json` | The full body of `POST /anomaly` — candidate, bounded same-category/currency history, and optional budget snapshot. |
| `anomaly-response.schema.json` | Deterministic risk statistics plus an LLM-written qualitative explanation. |
| `thresholds.json` | Not a schema — one shared numeric constant. `api`'s categorization confidence gate (`application.yml`'s `ledgerly.categorization.confidence-threshold`) and `ai`'s extraction self-check gate (`extraction_graph.CONFIDENCE_THRESHOLD`) must read the same value; a contract test on each side fails if its own constant diverges from `confidenceThreshold` here. |

`examples/` holds golden fixtures used by the contract tests on both sides:

| Example | Expected |
|---|---|
| `extraction-proposal.valid.json` | validates green |
| `extraction-proposal.missing-total.json` | validates red — `total_minor` is required |
| `extraction-proposal.float-amount.json` | validates red — money is an integer of minor units |
| `extract-request.valid.json` | validates green |
| `embed-policy-request.valid.json` | validates green |
| `embed-policy-response.valid.json` | validates green |
| `embed-policy-response.missing-chunks.json` | validates red — `chunks` is required |
| `embed-query-request.valid.json` | validates green |
| `embed-query-response.valid.json` | validates green |
| `categorize-request.valid.json` | validates green |
| `categorize-response.valid.json` | validates green |
| `categorize-response.missing-confidence.json` | validates red — `confidence` is required |

## Why money is an integer here

Every monetary field is `"type": "integer"` in minor units. A `number` would let a model emit
`121.50000000000001` and have it accepted by the schema, which is exactly the class of drift
architecture constraint C1 exists to prevent. The only non-integer in the proposal is a confidence
score, which is not money.

## Schema-valid is not the same as trustworthy

Passing this schema means the shape is right, nothing more. For extraction proposals, `api` applies
the schema to raw agent JSON before it constructs an `ExtractionProposal`; a missing required field,
unknown field, invalid format, or out-of-bounds value is a malformed response and reaches no posting
path. The arithmetic
(`total == sum(lines) + tax`), the currency allow-list, the date range and the organization's amount
ceiling are checked in `api` by `ExtractionProposalValidator` — the trust boundary described in
`docs/architecture.md`. A schema-valid proposal that fails those rules routes to `NEEDS_REVIEW` and
writes nothing to the ledger.
