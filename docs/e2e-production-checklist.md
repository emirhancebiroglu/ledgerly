# M10 T8 — Production end-to-end checklist

Manual verification against the live deploy, signed in as the demo account. Run through in
order; the upload flow (§4) depends on being signed in (§1) but nothing else is sequential.

**Environment**
- Web: https://ledgerly-ruby-two.vercel.app
- Demo login: `demo@ledgerly.dev` / `ledgerly-demo-account-2026`
- Open DevTools → Console before starting, and keep it open throughout. "No console errors"
  means no red entries at any point in this checklist, not just on initial page load.

**Test criterion (from docs/milestones.md T8):** every route loads with no console errors, the
uploaded invoice reaches a terminal status, and the ledger entries it produced sum to zero.

---

## 1. Sign in

- [ ] Go to `/login`, sign in with the demo credentials.
- [ ] Redirects to `/dashboard` (or `/`, whichever is the authenticated landing page).
- [ ] No console errors.

## 2. Every authenticated route loads clean

For each route, confirm: page renders (not a blank screen or error boundary), no console
errors, no infinite spinner.

- [ ] `/dashboard` — health widget (from M1), spending breakdown, trend section per currency.
- [ ] `/expenses` — list of demo-seeded expenses.
- [ ] `/expenses/[id]` — click into any one expense from the list. Ledger entries visible,
      "Balanced" badge shown (not a raw zero).
- [ ] `/review` — review queue table. If any demo-seeded expense is NEEDS_REVIEW, it's here.
- [ ] `/budgets` — budget list/detail.
- [ ] `/alerts` — alert list. Mark one read or dismiss one (if any exist) — sidebar's unread
      count should update without a hard reload (M10-era fix).
- [ ] `/policies` — policy document list.
- [ ] `/policies/[id]` — click into one policy document.
- [ ] `/upload` — upload page renders (don't upload yet, that's §4).
- [ ] `/health` — shows `Api: Healthy` and `Ai: Healthy` (already confirmed once during T5/T6,
      re-check here as part of the full sweep).

## 3. Cross-service / R2 sanity

- [ ] Open a policy document from `/policies/[id]` that has an attached file, or any other
      view that reads a stored document back (e.g. an expense's source document, if the UI
      exposes it). Confirm the file/content actually loads — this is what proves R2 read access
      works, not just write access from the demo seed.

## 4. Real upload → extract → categorize → post

- [ ] Go to `/upload`. Upload a real invoice (any PDF/image — a sample from
      `scripts/demo_seed/pdfs/` works, or any real invoice you have).
- [ ] Watch the status update via the live connection (SSE) without a manual refresh:
      `PENDING → PROCESSING → EXTRACTED/NEEDS_REVIEW` (or `FAILED`, if the document is
      deliberately bad — don't use one for this run).
- [ ] Confirms in a **terminal** status (not stuck in `PENDING`/`PROCESSING`).
- [ ] If it posted (not NEEDS_REVIEW): open the resulting expense, confirm a category, a
      citation from the policy RAG, and a balanced ledger transaction.
- [ ] If it landed in NEEDS_REVIEW: go to `/review`, approve or correct it, confirm it then
      shows up as posted with a balanced transaction.
- [ ] **Ledger balance check** — the specific test criterion. Either:
      - the expense detail page's own balance display shows "Balanced" (not a raw imbalance), or
      - if you have DB access, run:
        ```sql
        SELECT SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END)
        FROM ledger_entry WHERE ledger_transaction_id = '<the new transaction id>';
        -- must be 0
        ```
- [ ] No console errors anywhere in this flow.

## 5. R2 persistence across a restart (carried over from T6)

- [ ] After the upload in §4 completes, note the expense/document id.
- [ ] Trigger a restart of `ledgerly-api` on Render (Manual Deploy → Restart, or wait for the
      next auto-deploy if one's already pending).
- [ ] Once back up (`/health` shows `Api: Healthy` again), reopen the same expense/document.
      Confirm the file/content still loads — proves the object survived in R2, not just in a
      container-local disk that a restart would have wiped.

---

## Recording what you find

For each unchecked box above, note: which route/step, what happened instead (screenshot if
useful), and any console error text verbatim. Anything that fails goes into a follow-up bug-fix
pass before T9 (README/demo recording) — no point recording a demo of a broken flow.
