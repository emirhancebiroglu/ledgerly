# Handoff: Ledgerly M7 Dashboard

> Mirrored 2026-07-28 from the Claude Design project
> `0c8633c7-9c28-4d78-9089-80d5b3d3fc26` ("M7 Dashboard Design"), file
> `design_handoff_m7_dashboard/README.md`. This copy is the one build sessions read — it does not
> require the design MCP. If the design project changes, re-mirror it rather than editing here.
>
> **Scope note (2026-07-28 planning):** the milestone was split into **M7a** (read/mutation API)
> and **M7b** (dashboard UI); see `projects/ledgerly/todo.md` in the Brain for the task list.
> Two deviations from this handoff were decided then:
> - **§6 Budgets is out of M7.** The `budget` table is M8 scope, so the Budgets nav item ships
>   disabled ("coming in a later milestone"), the same treatment §Shell already gives Alerts and
>   Policies. The screen itself is built in M8 alongside its API.
> - **Accent is frozen to indigo/violet `oklch(0.5 0.16 265)`, light theme only.** The handoff is
>   light-first and gives no dark values.
>
> `image-slot.js` is deliberately **not** mirrored: it is an omelette starter scaffold used only
> by the prototype's document-viewer slot, and the handoff says to recreate that spot as a real
> viewer. Same for `support.js` — the prototype's templating runtime is not to be ported.

## Overview
Design pass for Ledgerly's M7 milestone: the first real product UI (dashboard, expenses, expense detail, upload, review queue, budgets), replacing the M1 health-check-only screen. Direction: "calm financial intelligence" — single-metric trust (Mercury/Ramp/Stripe pattern), progressive disclosure, AI agent activity shown as a transparent, separate stream (never mixed into chat), restrained visual density, light-first.

## About the Design Files
The file `Ledgerly Dashboard.dc.html` is a **design reference built in HTML** — a clickable prototype showing intended layout, states, and interaction, not production code to copy directly. It runs standalone in a browser (open it directly) and uses a small custom templating runtime (`support.js`, loaded automatically) purely to drive the prototype — **do not port that runtime**. The task is to **recreate this design in the existing stack** (Next.js 16 App Router + React 19 + Tailwind v4 + shadcn/ui "base-nova" style, per architecture.md) using its established components and patterns, not to embed the HTML.

`image-slot.js` is a prototype-only placeholder widget (the drag-drop receipt viewer on the expense detail screen) — recreate that spot as a real document/image viewer bound to the uploaded file, not as this widget.

## Fidelity
**High-fidelity.** Colors, type, spacing, and component states below are final for this pass — recreate pixel-close using the codebase's oklch tokens and shadcn primitives. Sample data (vendors, amounts, review reasons) is placeholder — wire to real API data.

## Screens / Views
The prototype is a single-page app with client-side view switching (sidebar nav + `⌘K` palette). Each "view" below maps to a route per architecture.md's sitemap.

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
    - **Ledger entries**: 2 rows (debit/credit), account number in Geist Mono + label + amount.
    - **Agent activity timeline**: vertical stepper — dot (color-coded: neutral gray for normal steps, amber `oklch(0.7 0.14 70)` for a flagged step) + connecting line + label/timestamp/detail text per step (5 sample steps).
- **Components**: document viewer, field grid, ledger entry row, agent timeline stepper.

### 4. Upload (`/upload`)
- **Layout**: centered single column, max-width 640px.
  - Dashed drop-zone card (upload icon, "Drop a receipt or invoice", helper text). Click simulates an upload in the prototype.
  - On trigger: a card appears showing filename + size, then 5 sequential steps (Uploading → OCR extraction → Vendor/category matching → Drafting ledger entry → Complete), each with a status indicator: pending (gray ring) → active (spinning accent ring) → done (filled green circle + check, pops in). Steps reveal ~650ms apart to feel like live SSE progress.
- **Components**: drop zone, step list with 3-state indicator.

### 5. Review queue (`/review`)
- **Layout**: header line "N items need review before posting to the ledger" + "Approve selected" button (appears once ≥1 row is checked). Table: checkbox, vendor, amount, flagged-for reason (plain-language, e.g. "Amount mismatch vs OCR (62% confidence)"), row actions (Approve / Correct). Mobile: header hidden, fields stack per row.
- **Components**: bulk-select table, inline reason text, row actions.

### 6. Budgets (`/budgets`)
> **Deferred to M8** — see the scope note at the top of this file. Kept here so M8 inherits the
> design without a second handoff.

- **Layout**: 3-col card grid (1 col mobile). Each card: category name + status chip (On track / Near threshold / Over budget) top-left, small 44px radial progress ring top-right (conic-gradient, center shows %), spent amount (20px Geist Mono) + "of $X this month" caption, plus a linear progress bar restating the same %. Ring/bar color: green <85%, amber 85–99%, red ≥100%.
- **Components**: budget card, radial progress ring, linear progress bar, status chip.

### Shell (all screens)
- **Sidebar** (240px, desktop): logo mark (accent-colored rounded square with a small white ledger-page glyph) + wordmark, nav list (Dashboard, Expenses, Upload, Review queue [with count badge], Budgets, divider, Alerts, Policies — both marked "coming in a later milestone" — Settings), org switcher row pinned at the bottom (avatar initial + org name + chevron). On viewports <860px the sidebar becomes a fixed-position off-canvas drawer (slides in via `transform: translateX`, backdrop overlay, opened via a hamburger button in the topbar).
- **Topbar** (60px): page title (+ hamburger on mobile), `⌘K` search trigger (full "Search or jump to... ⌘K" pill on desktop, icon-only button on mobile) wired to a working command palette (meta/ctrl+K), user avatar circle.
- **Command palette**: centered modal, backdrop click or Escape-equivalent closes it, search input + 4 quick-jump rows.

## Interactions & Behavior
- Sidebar nav click switches the active view instantly (no route in the prototype; use real Next.js routing in the app) and highlights the active item (soft accent background + accent text).
- `⌘K`/`Ctrl+K` toggles the command palette from anywhere; clicking a palette row navigates and closes it.
- Upload drop-zone click starts a simulated 5-step sequence, ~650ms per step, auto-advancing; recreate as real SSE-driven progress in the app.
- Review queue: row checkbox toggles selection; "Approve selected" and per-row "Approve" remove the row (optimistic); wire to the actual approve/dismiss/correct mutations.
- All interactive rows/buttons/nav items have hover/active micro-states: rows tint lightly on hover, buttons lift 1px with a soft shadow, palette entrance is a quick fade+scale, card content fades/slides up on view entry.
- Reduced motion: keep transitions short (120–400ms) and avoid motion-only affordances; add `prefers-reduced-motion` handling in the real build (not present in the prototype).

## State Management
- Active view/route.
- Selected expense id (for detail view).
- Command palette open/closed.
- Review queue: per-row selection map, and list mutation on approve.
- Upload: active/step index (replace the timer simulation with real SSE progress events).
- Viewport width → mobile vs desktop layout switch (breakpoint 860px) and mobile-nav-drawer open/closed.

## Design Tokens
- **Font**: Geist (UI/headings), Geist Mono (all numbers/money — tabular-nums required for alignment).
- **Accent**: indigo/violet `oklch(0.5 0.16 265)` — **frozen at planning**, the prototype's
  blue-green alternate `oklch(0.56 0.11 195)` is not used; soft tint `oklch(0.95 0.035 265)`; accent text `oklch(0.4 0.14 265)`.
- **Neutrals**: page bg `oklch(0.99 0.002 265)`, sidebar bg `oklch(0.975 0.003 265)`, card bg `oklch(1 0 0)`, border `oklch(0.91 0.006 265)` (subtle divider `oklch(0.95 0.003 265)`), text primary `oklch(0.22 0.02 265)`, text secondary `oklch(0.55 0.01 265)`.
- **Semantic**: success/on-track `oklch(0.6 0.13 145)` on `oklch(0.95 0.04 145)`; warning/near-threshold `oklch(0.7–0.75 0.14 70–80)` on `oklch(0.96 0.05 80)`; destructive/over-budget `oklch(0.6 0.16 25)` on `oklch(0.95 0.05 25)`. Every status is paired with a distinct icon/shape, never color alone (colorblind-safe).
- **Radius**: 12px cards, 8px buttons/chips/inputs, 6–7px small badges/logo mark.
- **Shadow** (card depth): `0 1px 2px oklch(0.2 0.02 265 / 0.04), 0 8px 20px oklch(0.2 0.02 265 / 0.03)`.
- **Density prop** (tweakable): comfortable (14px vertical row padding) vs compact (8px).
- **Spacing**: 20–28px page gutters desktop, 16–18px mobile; 16–20px gaps between cards.

## Assets
No external imagery — the logo mark and all icons are inline SVG (simple line icons, ~1.6–2px stroke). The expense-detail document viewer is a placeholder image slot in the prototype; source real receipt/invoice thumbnails in the app.

## Files
- `Ledgerly Dashboard.dc.html` — the full interactive prototype (open directly in a browser).
- `image-slot.js` — supporting placeholder widget used only by the prototype's document-viewer slot (not for reuse; not mirrored here).
