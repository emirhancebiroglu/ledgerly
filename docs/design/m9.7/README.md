# Handoff: Ledgerly M9.7 Policies

> Design pass for the Policies section, commissioned 2026-08-22 against Ledgerly's real policy
> model. `Ledgerly Dashboard.dc.html` here is a **later revision of the same prototype** as
> `docs/design/m7/Ledgerly Dashboard.dc.html` — it carries every M7 screen plus the two new
> Policies screens and the M9.5 Alerts screen. The M7 directory stays as the authority for the
> screens it covers; this README is the implementation authority for Policies only.

## Overview

Policies is the last disabled nav item in the shell. It makes the policy-RAG capability that
M6 shipped — policy PDFs chunked, embedded, and retrieved during categorization — visible for
the first time. Direction is unchanged from M7: calm financial intelligence, progressive
disclosure, restrained density, light-first.

## What a policy is in this product

A policy is **not a rule engine**. It is a **PDF document an operator uploads**. On upload the
API stores the blob, asks `ai` to chunk and embed it, and persists the resulting `policy_chunk`
rows. Later, when `ExpensePostingService` categorizes an expense, it retrieves the nearest
chunks and may store one as `expense.citation` — after checking the quote against the stored
chunk text, so a fabricated quote is scrubbed to `null` rather than saved.

The section therefore answers two operator questions:

1. What policy documents does my organization have, and did they process correctly?
2. What text is the AI actually reading when it justifies a categorization?

The second question is the point of the detail screen and nothing in the product surfaces it
today.

## About the design file

`Ledgerly Dashboard.dc.html` is a **design reference built in HTML** — a clickable prototype
showing layout, states and interaction, not production code to copy. It runs standalone in a
browser and uses a small custom templating runtime (`support.js`) purely to drive the prototype
— **do not port that runtime**. `image-slot.js` is a prototype-only placeholder widget used by
the expense-detail screen; it is unrelated to Policies and is carried here only because the
prototype imports it.

Recreate the design in the existing stack (Next.js App Router + React 19 + Tailwind v4 +
shadcn/ui) using the repo's established components and oklch tokens, not the prototype's raw
literals.

**Prototype-only controls that must not ship:** the `Prototype states` row under the policy
list (Populated / Loading / Load error / Empty org) and the `Prototype` row inside the upload
panel (Valid PDF / Non-PDF file / Embedding failure). Both exist to make otherwise-unreachable
states clickable in a static file. In the real build those states come from the API.

## Fidelity

**High-fidelity.** Colors, type, spacing and component states are final for this pass. Sample
filenames and passage text are placeholder — wire to real API data.

## Screens

### 1. Policies list (`/policies`)

Content column, `max-width:1080px`.

- **Intro line + primary action.** One sentence stating what the screen is ("Policy PDFs
  indexed for retrieval. When Ledgerly categorizes an expense it reads the nearest passages
  from these documents and may quote one as its justification."), with an `Upload policy PDF`
  button on the right that opens the upload panel inline.
- **Upload panel** (collapsed until invoked) — see *Upload states* below.
- **Stat row**, 3 cards, all derived, never hardcoded:
  - `Documents indexed` — count of `EMBEDDED`, noted `of N uploaded`.
  - `Passages indexed` — sum of chunk counts across `EMBEDDED` documents, noted
    `retrievable across the org`.
  - `Failed` — count of `FAILED`, noted `needs re-upload`, or `nothing to fix` at zero. The
    value turns destructive-red only when non-zero.
- **Filter tabs** with counts: All / Indexed / Processing / Failed. `Processing` groups both
  `PROCESSING` and `PENDING`, because the difference is an internal scheduling detail an
  operator cannot act on.
- **Rows**, one card per document: file icon tinted by status, filename in Geist Mono, status
  chip, chunk count (`12 chunks`, or `—` when not `EMBEDDED`), upload date, chevron. Clicking
  opens the detail route.
- A `FAILED` row expands inline with its failure explanation and the raw `failureReason` in a
  mono block — the operator sees what to fix without navigating.
- **Empty organization** state (no policy ever uploaded): this is the common first-run case and
  carries a three-step explanation of why the section matters, not a one-line shrug.
- **Filter-empty**, **loading** (skeleton rows) and **load-error** states are all shipped
  states.

### 2. Policy detail (`/policies/[id]`)

Content column, `max-width:1080px`, two columns (main + 300px aside) collapsing to one at
860px.

- **Back link** to the list, then filename as the title in Geist Mono with its status chip.
  Meta line states passage count and searchability in plain language.
- **Main column — indexed passages.** The heart of the screen:
  - Header row: section label, a search input filtering passage text, and a match summary.
  - One row per passage: zero-padded index in Geist Mono, quiet, left; passage text at
    `max-width:66ch`, `line-height:1.68`.
  - Folded at 6 passages with a `Show N more` control carrying the remaining count.
  - Search with no match states "The AI can only quote text that exists in the document."
  - A passage that has been cited by an expense carries an accent chip linking to that expense.
- **Failed document**: a destructive-bordered card replacing the passage list —
  "Embedding failed — no passages were stored", explicit that the document is **not partially
  indexed**, the raw `failureReason`, and a `Re-upload this document` action. Never an empty
  list that reads as a bug.
- **Processing / queued**: spinner card stating nothing is retrievable until embedding
  finishes.
- **Detail loading**: skeleton.
- **Aside — Document facts**: Filename, Status (raw enum value), Uploaded, Passages.
- **Aside — How this text is used**: one paragraph explaining retrieval and the citation
  verification guarantee.

### Upload states

Upload is **synchronous** — `PolicyUploadService` does not return until the outcome is final.
The panel therefore models a real several-second request, not an optimistic row.

- **Idle**: dashed drop zone, "PDF only. Embedding runs during the upload and takes a few
  seconds — keep this tab open until it finishes."
- **In-flight**: spinner, filename, indeterminate progress bar, "This request stays open until
  the outcome is final."
- **Success**: green card naming the passage count, `View document` and `Upload another`.
- **Failed**: destructive card, plain-language consequence plus raw `failureReason`, `Re-upload`.
- **Rejected (non-PDF)**: dashed destructive card — the file was never uploaded. Matches
  `DetectedContentType.detect` rejecting non-PDF content at the API.

## Implementation decisions

- **Status vocabulary.** The API's four values are shown to the operator as `Indexed`
  (`EMBEDDED`), `Processing`, `Queued` (`PENDING`) and `Failed`. The raw enum is still shown
  verbatim in the detail aside's `Status` fact, so the mapping never hides the real value.
- **`PENDING` and `PROCESSING` are rare but real.** Because upload is synchronous, an operator
  reaches these states only by viewing the list in another tab while an upload is in flight.
  They are built because the rows genuinely exist in the database during that window, not
  because they are common.
- **Every stat is computed from the listed documents.** No time-series, no percentages, no
  "this month" counts — the data to support them does not exist.
- **Citation provenance chip: dropped for this pass, not shipped.** `expense.citation` stores
  the chunk *text*, not a chunk id, in an unindexed `TEXT` column with no repository query
  against it today. Answering "which expense quoted this passage" without an N+1 is feasible —
  one `findByOrganizationIdAndCitationIn(orgId, chunkTexts)` query per detail-page load — but
  building it means adding a query method to `ExpenseRepository` and a field to
  `PolicyChunkResponse`, both in `apps/api/**`, which is outside M9.7b's freeze (`apps/web/**`
  only; M9.7a already closed). Revisiting this is a small, well-scoped addition to a future
  policy milestone, not a technical blocker — it just doesn't fit inside a FE-only pass.
- **Nothing mutates a policy.** No rename, no edit, no enable/disable, no delete. Documents are
  immutable once uploaded; re-uploading is the only corrective action, and it creates a new
  document rather than replacing one.

## Deliberately not built

Each of these appeared in an earlier design iteration and was cut because Ledgerly has no
mechanism behind it. Reintroducing any of them repeats the untruthful card M9.4 removed:

toggles or enable/disable · conditions, operators, thresholds, a rule builder · enforcement
modes (Blocking / Review only) · "Held N expenses", "auto-approved %", or any enforcement
statistic · enforcement or activity timelines · version numbers, owners, authors, approvers ·
effective dates · scopes, teams, roles · draft / paused / archived states · editing, renaming
or deleting a policy · tags, categories or folders.

If a layout feels thin without one, the passage text carries more weight — the fiction does not
come back.

## Design tokens

Unchanged from M7 (`docs/design/m7/README.md` is authoritative). Used here:

- **Font**: Geist for UI; Geist Mono for filenames, passage indices, counts and raw enum
  values, with `tabular-nums` on aligned figures.
- **Accent**: `oklch(0.5 0.16 265)`; soft `oklch(0.95 0.035 265)`; accent text
  `oklch(0.4 0.14 265)`.
- **Neutrals**: page `oklch(0.99 0.002 265)`, card `oklch(1 0 0)`, border
  `oklch(0.91 0.006 265)`, divider `oklch(0.95 0.003 265)`, text `oklch(0.22 0.02 265)`,
  muted `oklch(0.55 0.01 265)`.
- **Semantic**: indexed/success `oklch(0.6 0.13 145)` on `oklch(0.95 0.04 145)`; failed
  `oklch(0.6 0.16 25)` on `oklch(0.95 0.05 25)`. Every status pairs colour with a distinct
  icon — check, spinner, clock, triangle — never colour alone.
- **Radius**: 12px cards, 8px buttons/inputs, 6–7px chips.
- **Shadow**: `0 1px 2px oklch(0.2 0.02 265 / 0.04), 0 8px 20px oklch(0.2 0.02 265 / 0.03)`.
- **Breakpoint**: 860px — sidebar to drawer, detail grid to one column, rows stack.
- **Motion**: 120–400ms; spinner, indeterminate bar and skeleton pulse are the only loops. Add
  `prefers-reduced-motion` handling in the real build.

## Files

- `Ledgerly Dashboard.dc.html` — the prototype (open directly in a browser).
- `support.js` — prototype-only templating runtime; do not port.
- `image-slot.js` — prototype-only placeholder used by expense detail; unrelated to Policies.
