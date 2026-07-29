# Handoff: Ledgerly M7 Dashboard

> Consolidated for M9.1 on 2026-07-29. `Ledgerly Dashboard.dc.html` is the original dashboard
> reference; `Ledgerly Auth.dc.html` is the later Claude Design authentication reference. This
> README is the implementation authority where the prototypes contain sample data or capabilities
> that Ledgerly does not yet ship.

## Overview
Design pass for Ledgerly's M7 milestone: authentication (login, register) plus the first real product UI (dashboard, expenses, expense detail, upload, review queue, budgets), replacing the M1 health-check-only screen. Direction: "calm financial intelligence" — single-metric trust (Mercury/Ramp/Stripe pattern), progressive disclosure, AI agent activity shown as a transparent, separate stream (never mixed into chat), restrained visual density, light-first.

## About the Design Files
The files `Ledgerly Dashboard.dc.html` and `Ledgerly Auth.dc.html` are **design references built in HTML** — a clickable prototype showing intended layout, states, and interaction, not production code to copy directly. It runs standalone in a browser (open it directly) and uses a small custom templating runtime (`support.js`, loaded automatically) purely to drive the prototype — **do not port that runtime**. The task is to **recreate this design in the existing stack** (Next.js 16 App Router + React 19 + Tailwind v4 + shadcn/ui "base-nova" style, per architecture.md) using its established components and patterns, not to embed the HTML.

`image-slot.js` is a prototype-only placeholder widget (the drag-drop receipt viewer on the expense detail screen) — recreate that spot as a real document/image viewer bound to the uploaded file, not as this widget.

## Fidelity
**High-fidelity.** Colors, type, spacing, and component states below are final for this pass — recreate pixel-close using the codebase's oklch tokens and shadcn primitives. Sample data (vendors, amounts, review reasons) is placeholder — wire to real API data.

## M9.1 implementation decisions

- **Visual system:** indigo/violet `oklch(0.5 0.16 265)`, light-only, and comfortable density
  are frozen. There is no blue-green variant, dark theme, or compact-density control. Use 860px
  for the app shell and 900px for the auth shell.
- **Truthful authentication copy:** use “From receipt to a review-ready ledger entry.” and
  “Ledgerly extracts document data, categorizes the expense, drafts a balanced posting, and routes
  low-confidence cases to review.” Proof text is “100% / of posted transactions balanced” and
  “5 steps / visible from upload to outcome”; the footer is “Organization-scoped access · Exact
  minor-unit arithmetic · Auditable agent activity.” Do not use SOC 2, bank-grade, 94%, 11h, or
  any other unverified claim.
- **Authentication scope:** Google and enterprise SSO are disabled buttons labelled `Coming soon`;
  they never submit or navigate. Forgot-password, Terms, Privacy, consent links, email
  verification, MFA, and invites are omitted rather than rendered as dead controls. Registration
  requires full name, company, email, and a password of at least 12 characters.
- **Budget behavior:** M8 budgets and alerts remain live. Warning begins at 80%, over-budget at
  100%; status must use icon, text, and color. Budget management is a responsive 560px accessible
  dialog with list, create, edit, and confirmed delete. Currency is entered as a human decimal and
  converted losslessly to minor units; raw minor units are never shown as an input.
- **Truthful ledger/detail data:** show six extracted fields (vendor, invoice number when present,
  date, amount, tax, confidence) from the validated proposal. Ledger rows use real account name
  and debit/credit direction; Ledgerly has no ledger account-number display.
- **Agent activity:** upload, extraction, categorization, ledger drafting, and terminal outcome
  are persisted, ordered records. Live SSE only continues that history; it never invents progress
  with timers. Empty, loading, error, disabled, mobile, and reduced-motion states are all shipped
  states, not prototype omissions.

## Screens / Views
Two prototype files. `Ledgerly Auth.dc.html` covers the unauthenticated screens (0a/0b below); `Ledgerly Dashboard.dc.html` is the authenticated app — a single-page app with client-side view switching (sidebar nav + `⌘K` palette). Each "view" below maps to a route per architecture.md's sitemap.

### 0a. Login (`/login`)
- **Purpose**: fastest possible return to the ledger.
- **Layout**: full-height split. Left **brand panel** (44% width, max 620px, desktop ≥900px only): deep indigo `oklch(0.28 0.07 265)` with two large soft blurred accent circles, logo lockup, the truthful copy fixed above, and two proof stats. Right **form panel**: centered column, max-width 400px.
- **Form panel contents (top to bottom)**: heading, two disabled SSO buttons labelled `Coming soon`, divider, **Work email** and **Password** fields (42px with icon affixes and password reveal), 30-day remember-me checkbox, primary submit with pending label, recovery-oriented error banner, and register link. There is no Forgot control.
- **Mobile (<900px)**: brand panel hidden; form panel is full width with 32px/20px padding, small logo lockup, and the truthful footer line.

### 0b. Register (`/register`)
Same shell and layout as login — it is the same screen with a mode switch, so build them as one component with a variant, or two routes sharing a layout.
- **Differences from login**: heading "Create your workspace" + a reassurance subheading; a 2-column row above email for **Full name** and **Company** (stacks only when necessary); password placeholder "At least 12 characters" and a four-segment strength meter; no remember-me or legal-consent checkbox; submit reads "Create workspace" / "Creating workspace..."; footer links to sign in.
- **Components (both)**: split auth layout, brand panel, SSO button, labeled input field with icon affixes, password field with reveal toggle, password strength meter, checkbox row, primary button with loading state, inline error banner, mode-switch footer link.
- **Field focus state**: border shifts to `oklch(0.6 0.13 265)` with a 3px accent focus ring at 12% opacity — implement on the wrapper (`:focus-within`), not the bare input.
- **Out of scope:** password reset, email verification, MFA challenge, and invite acceptance are absent until their full flows are designed and implemented.

### 1. Dashboard (`/dashboard`)
- **Purpose**: answer "is everything okay?" in one glance, then drill down.
- **Layout**: content column, `max-width:1080px`, `gap:20px`.
  - Row 1: 2-col grid (`1.1fr 1fr` desktop, 1 col mobile <860px):
    - **KPI card**: label "Total spend this month" (12.5px, muted) + inline mini sparkline (SVG polyline, 90×30, accent stroke) top-right. Big number 38px Geist Mono, tabular-nums, weight 600. Delta pill next to it (green bg `oklch(0.95 0.04 145)`, text `oklch(0.55 0.13 145)`, up/down arrow icon, e.g. "12%"). Caption below ("vs $95,690 last month"). Optional insight callout box below: accent-soft background, accent text, sparkle icon + one sentence of generated insight copy (toggleable — see Design Tokens/props).
    - **Summary card**: 3 label/value rows (Review queue count, Budgets at threshold, Documents processed today), each `justify-content:space-between`, value in Geist Mono, plus a full-width primary CTA button "Go to review queue" at the bottom (accent bg, white text).
  - Row 2: 2-col grid (1 col mobile):
    - **Category breakdown**: list of category rows, each a label+amount line above a 6px rounded horizontal progress bar (accent fill, width = % of total).
    - **Spend over time**: line chart, SVG polyline + circle data points, accent stroke, 6 months, month labels below.
  - Row 3: **Recent expenses** card — list of 5 rows (no table header), each row: vendor (13px/500), category (12.5px muted), amount (Geist Mono, tabular-nums), status chip. Row click → Expense detail. "View all" link top-right → Expenses list.
- **Components**: stat card, progress-bar chart, line chart (SVG), status chip, list row.

### 2. Expenses list (`/expenses`)
- **Layout**: search input + 2 filter chips (status, sort) in a row, then a bordered card containing a header row (Vendor/Category/Date/Amount/Status, hidden on mobile) and one row per expense (10 sample rows). Rows are clickable → Expense detail. Mobile: header hidden, each row's 5 fields stack vertically in one column (no horizontal scroll).
- **Components**: search field, filter chip, data table row, status chip.

### 3. Expense detail (`/expenses/[id]`)
- **Layout**: "Back to expenses" link, then 2-col grid (1 col mobile, document above fields):
  - Left: sticky **document viewer** card (placeholder in the prototype — recreate as real receipt/invoice image or PDF preview).
  - Right, stacked cards:
    - **Extracted fields**: vendor name + status chip header, then a 2-col grid of label/value pairs (Vendor, Invoice #, Date, Amount, Tax, Confidence) — values in Geist Mono.
    - **Ledger entries**: 2 rows (debit/credit), real account name + direction + amount. Do not invent account numbers.
    - **Agent activity timeline**: vertical stepper — dot (color-coded: neutral gray for normal steps, amber `oklch(0.7 0.14 70)` for a flagged step) + connecting line + label/timestamp/detail text per step (5 sample steps).
- **Components**: document viewer, field grid, ledger entry row, agent timeline stepper.

### 4. Upload (`/upload`)
- **Layout**: centered single column, max-width 640px.
  - Dashed drop-zone card (upload icon, "Drop a receipt or invoice", helper text). Selection starts a real upload.
  - On trigger: a card appears showing filename + size, then the five persisted stages (Uploading → OCR extraction → Vendor/category matching → Drafting ledger entry → terminal outcome), each with a status indicator: pending (gray ring) → active (spinning accent ring) → done (filled green circle + check, pops in). The UI changes only when the activity record changes.
- **Components**: drop zone, step list with 3-state indicator.

### 5. Review queue (`/review`)
- **Layout**: header line "N items need review before posting to the ledger" + "Approve selected" button (appears once ≥1 row is checked). Table: checkbox, vendor, amount, flagged-for reason (plain-language, e.g. "Amount mismatch vs OCR (62% confidence)"), row actions (Approve / Correct). Mobile: header hidden, fields stack per row.
- **Components**: bulk-select table, inline reason text, row actions.

### 6. Budgets (`/budgets`)
- **Layout**: 3-col card grid at ≥860px and 1 column below. Each card: category name + status chip (On track / Near threshold / Over budget) top-left, 44px radial progress ring top-right, spent amount (20px Geist Mono) + "of $X this month" caption, and linear progress bar. Ring/bar color: green <80%, amber 80–99%, red ≥100%; the ring caps visually at 100% while its label keeps the true ratio.
- **Components**: budget card, radial progress ring, linear progress bar, status chip.

### Shell (all screens)
- **Sidebar** (240px, desktop): logo mark (accent-colored rounded square with a small white ledger-page glyph) + wordmark, nav list (Dashboard, Expenses, Upload, Review queue [with count badge], Budgets, divider, Alerts, Policies — both marked "coming in a later milestone" — Settings), org switcher row pinned at the bottom (avatar initial + org name + chevron). On viewports <860px the sidebar becomes a fixed-position off-canvas drawer (slides in via `transform: translateX`, backdrop overlay, opened via a hamburger button in the topbar).
- **Topbar** (60px): page title (+ hamburger on mobile), `⌘K` search trigger (full "Search or jump to... ⌘K" pill on desktop, icon-only button on mobile) wired to a working command palette (meta/ctrl+K), user avatar circle.
- **Command palette**: centered modal, backdrop click or Escape-equivalent closes it, search input + 4 quick-jump rows.

## Interactions & Behavior
- Auth: disabled SSO controls do not lift, submit lifts 1px with a soft shadow on hover, pending state follows the real request, the eye toggles password visibility, and the footer link switches login ↔ register. Validate on blur and keep errors recovery-oriented.
- Sidebar nav click switches the active view instantly (no route in the prototype; use real Next.js routing in the app) and highlights the active item (soft accent background + accent text).
- `⌘K`/`Ctrl+K` toggles the command palette from anywhere; clicking a palette row navigates and closes it.
- Upload is driven by persisted activity plus replayable SSE; do not use timer-simulated progress.
- Review queue: row checkbox toggles selection; "Approve selected" and per-row "Approve" remove the row (optimistic); wire to the actual approve/dismiss/correct mutations.
- All interactive rows/buttons/nav items have hover/active micro-states: rows tint lightly on hover, buttons lift 1px with a soft shadow, palette entrance is a quick fade+scale, card content fades/slides up on view entry.
- Reduced motion: keep transitions short (120–400ms) and avoid motion-only affordances; add `prefers-reduced-motion` handling in the real build (not present in the prototype).

## State Management
- Auth: form mode (login/register), password visibility, submitting flag, error state, and viewport width (brand panel shows/hides at 900px). Field values themselves are uncontrolled in the prototype — use the codebase's form library.
- Active view/route.
- Selected expense id (for detail view).
- Command palette open/closed.
- Review queue: per-row selection map, and list mutation on approve.
- Upload: active/step index (replace the timer simulation with real SSE progress events).
- Viewport width → mobile vs desktop layout switch (breakpoint 860px) and mobile-nav-drawer open/closed.

## Design Tokens
- **Font**: Geist (UI/headings), Geist Mono (all numbers/money — tabular-nums required for alignment).
- **Accent**: indigo/violet `oklch(0.5 0.16 265)`; soft tint `oklch(0.95 0.035 265)`; accent text `oklch(0.4 0.14 265)`.
- **Neutrals**: page bg `oklch(0.99 0.002 265)`, sidebar bg `oklch(0.975 0.003 265)`, card bg `oklch(1 0 0)`, border `oklch(0.91 0.006 265)` (subtle divider `oklch(0.95 0.003 265)`), text primary `oklch(0.22 0.02 265)`, text secondary `oklch(0.55 0.01 265)`.
- **Semantic**: success/on-track `oklch(0.6 0.13 145)` on `oklch(0.95 0.04 145)`; warning/near-threshold `oklch(0.7–0.75 0.14 70–80)` on `oklch(0.96 0.05 80)`; destructive/over-budget `oklch(0.6 0.16 25)` on `oklch(0.95 0.05 25)`. Every status is paired with a distinct icon/shape, never color alone (colorblind-safe).
- **Radius**: 12px cards, 8px buttons/chips/inputs, 6–7px small badges/logo mark.
- **Auth-specific**: brand panel bg `oklch(0.28 0.07 265)` with decorative circles `oklch(0.42 0.13 265 / 0.55)` and `oklch(0.5 0.12 265 / 0.3)`; panel text `oklch(0.97 0.005 265)` / muted `oklch(0.82–0.85 0.03 265)`; form control height 42px throughout; focus ring `0 0 0 3px oklch(0.5 0.16 265 / 0.12)`.
- **Shadow** (card depth): `0 1px 2px oklch(0.2 0.02 265 / 0.04), 0 8px 20px oklch(0.2 0.02 265 / 0.03)`.
- **Density**: comfortable only (14px vertical row padding).
- **Spacing**: 20–28px page gutters desktop, 16–18px mobile; 16–20px gaps between cards.

## Assets
No external imagery — the logo mark and all icons are inline SVG (simple line icons, ~1.6–2px stroke). The expense-detail document viewer is a placeholder image slot in the prototype; source real receipt/invoice thumbnails in the app.

## Breakpoints
Single breakpoint in both prototypes, driven by a width listener: **860px** for the app shell (sidebar → drawer, grids → single column, tables → stacked rows) and **900px** for auth (brand panel hidden). In the real build, express these as Tailwind breakpoints (`lg:`) rather than JS width state.

## Files
- `Ledgerly Dashboard.dc.html` — the authenticated app prototype (open directly in a browser).
- `Ledgerly Auth.dc.html` — the login/register prototype; the mode switch and password-strength score are exposed as adjustable props.
- `support.js` — prototype-only templating runtime; do not port it.
- `image-slot.js` — prototype-only document-viewer placeholder; replace it with the real stored document viewer.
- `.thumbnail` — rendered preview asset for visual reference only; it is not a production asset.
