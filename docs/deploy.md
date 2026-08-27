# Deploying Ledgerly

Reproducible click-path for a from-scratch production deploy: `api` + `ai` on Render, `web`
on Vercel, object storage on Cloudflare R2, uptime monitoring on UptimeRobot. Follow the
sections in order — later steps depend on URLs/values earlier steps produce.

Nothing here is a secret value. Every credential is described by where to generate it and
where to paste it, never by its actual value.

## 1. Cloudflare R2 (object storage)

1. Cloudflare dashboard → **R2 Object Storage** → **Create bucket**. Name it (e.g.
   `ledgerly-prod`), leave default region ("Automatic").
2. **R2 → Manage API tokens → Create API token**. Permissions: **Object Read & Write**,
   scoped to the bucket just created. Cloudflare shows the **Access Key ID** and
   **Secret Access Key** once — copy both now, they're needed in Render (§3).
3. **Account ID**: R2 landing page, right sidebar — this is the account-level ID R2 uses in
   its endpoint URL, not the API token.
4. CORS: `apps/api`'s `R2StorageClient` only ever calls R2 server-side (`putObject`/
   `getObject` from the API container) — the browser never talks to R2 directly, so no CORS
   rule is required for the app to function. Leave the bucket's CORS policy empty unless a
   future feature adds direct browser-to-R2 access (e.g. presigned upload URLs), at which
   point add a rule allowing the Vercel origin from §5.

Values collected here: **Account ID**, **Access Key ID**, **Secret Access Key**, **bucket
name** — go into Render as `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`,
`R2_BUCKET`.

## 2. Sentry (error tracking, optional but wired)

Two separate Sentry projects (or one project, two DSNs — either works): one for `api`
(Java), one for `ai` (Python). `web` uses a third, `NEXT_PUBLIC_SENTRY_DSN` (§5) — already
public by nature since it ships in the client bundle.

Each project's **Settings → Client Keys (DSN)** page has the DSN string to copy.

## 3. Render (`api` + `ai` + Postgres)

### 3.1 Create the Blueprint

1. Render dashboard → **New → Blueprint**.
2. Connect the `ledgerly` GitHub repo (Render will ask for repo access via GitHub App
   install if not already granted).
3. Render reads `render.yaml` from the repo root and shows the three resources it will
   create: `ledgerly-postgres` (database), `ledgerly-api`, `ledgerly-ai` (web services).
4. Render prompts for every `sync: false` variable during this step — see §3.2 for what to
   enter for each. Fields left blank at this point can be filled in later from each
   service's **Environment** tab.
5. Click **Apply**. First build takes a few minutes per service (Docker image build).

### 3.2 Secrets — source and destination

Every variable `render.yaml` marks `sync: false`, what it is, and where its value comes
from:

| Variable | Service | Source |
|---|---|---|
| `JWT_SECRET` | `ledgerly-api` | Generate a random 256-bit+ string, e.g. `openssl rand -base64 48`. Anything long and random — this signs auth tokens, not a shared external credential. |
| `AI_BASE_URL` | `ledgerly-api` | `ledgerly-ai`'s public URL once it has deployed once (Render → `ledgerly-ai` → the `.onrender.com` URL at the top of the service page), e.g. `https://ledgerly-ai.onrender.com`. Circular with the blueprint's own first apply — see §3.3. |
| `AI_SERVICE_TOKEN` | **both** `ledgerly-api` and `ledgerly-ai` | Generate a random string (same method as `JWT_SECRET`). Must be the **identical value** in both services — `DocumentQueuePoller` (`api`) authenticates its calls into `ai` with it. |
| `CORS_ALLOWED_ORIGINS` | `ledgerly-api` | The Vercel production domain from §5, e.g. `https://ledgerly-ruby-two.vercel.app`. Plain string, no brackets. |
| `R2_ACCOUNT_ID` | `ledgerly-api` | §1.3. |
| `R2_ACCESS_KEY_ID` | `ledgerly-api` | §1.2. |
| `R2_SECRET_ACCESS_KEY` | `ledgerly-api` | §1.2. |
| `R2_BUCKET` | `ledgerly-api` | §1.1 (the bucket name chosen). |
| `SENTRY_DSN` | `ledgerly-api` | §2, the `api`-project DSN. |
| `AI_CORS_ORIGINS` | `ledgerly-ai` | Same Vercel domain as `CORS_ALLOWED_ORIGINS`, but as a **JSON array string**: `["https://ledgerly-ruby-two.vercel.app"]` — pydantic-settings parses this env var as JSON, not a bare comma-separated list. |
| `AI_LLM_API_KEY` | `ledgerly-ai` | LiteLLM-compatible provider key (whichever LLM provider is configured — see `docs/decisions.md` for the provider choice). |
| `AI_EMBEDDING_API_KEY` | `ledgerly-ai` | Voyage AI API key — **not** the same value as `AI_LLM_API_KEY`; the two providers are separate accounts (`docs/decisions.md`, 2026-08-25). |
| `AI_SENTRY_DSN` | `ledgerly-ai` | §2, the `ai`-project DSN. |

Non-secret values (`PORT`, `SERVER_PORT`, `RATE_LIMIT_BACKEND`, `DOCUMENT_EVENT_BROKER`,
`SENTRY_ENVIRONMENT`, `AI_LLM_PROVIDER`, `AI_EMBEDDING_PROVIDER`, `AI_SENTRY_ENVIRONMENT`,
`DATABASE_URL`) are already plain values or `fromDatabase` references in `render.yaml` —
nothing to enter for those.

`AI_RATE_LIMIT_REDIS_URL` is deliberately absent from `render.yaml` — leaving it unset
selects the in-process rate limiter (no managed Redis on the free tier, per M9.9). Do not
add it.

### 3.3 The `AI_BASE_URL` circularity

`AI_BASE_URL` (needed by `ledgerly-api`) is `ledgerly-ai`'s own URL, which only exists after
`ledgerly-ai` has deployed at least once. Handle it in this order:

1. Apply the blueprint leaving `AI_BASE_URL` blank (Render allows an empty `sync: false`
   value at creation).
2. Once `ledgerly-ai` shows a live URL, copy it.
3. `ledgerly-api` → **Environment** tab → set `AI_BASE_URL` to that URL → save. This
   triggers a redeploy of `ledgerly-api` alone.

Same pattern applies if `CORS_ALLOWED_ORIGINS` / `AI_CORS_ORIGINS` aren't known yet (Vercel
domain from §5 comes after Render in a from-scratch run) — leave blank, fill in and redeploy
once §5 is done.

### 3.4 pgvector

No manual step: `ledgerly-postgres` is Render managed Postgres 17, which includes pgvector.
The `CREATE EXTENSION IF NOT EXISTS vector` statement lives in the Flyway migration and runs
automatically on `ledgerly-api`'s first boot. (Verified directly against a Render Postgres
instance — `SELECT extversion FROM pg_extension WHERE extname = 'vector'` returned `0.8.0`.)

## 4. UptimeRobot

1. [uptimerobot.com](https://uptimerobot.com) → **Add New Monitor**.
2. Monitor type: **HTTP(s)**.
3. Create **two** monitors, one per service, both **5-minute** interval:
   - `ledgerly-api`'s Render URL + `/actuator/health` (the same path `render.yaml` uses as
     `healthCheckPath`), e.g. `https://ledgerly-api.onrender.com/actuator/health`.
   - `ledgerly-ai`'s Render URL + `/health`, e.g. `https://ledgerly-ai.onrender.com/health`.
4. Both are required, not just `ledgerly-api`: Render's free plan spins down each service
   *independently* after 15 minutes of its own idle time — a warm `ledgerly-api` does nothing
   to keep `ledgerly-ai` awake. `ledgerly-api` calls `ledgerly-ai` synchronously mid-request
   during document extraction; if `ledgerly-ai` has gone to sleep, that call blocks for the
   length of `ledgerly-ai`'s own cold start (up to a minute or more), which is long enough for
   the browser's SSE connection watching the upload to drop and the demo user's JWT to expire
   before the extraction ever finishes. This was caught by a real production upload landing in
   this exact stuck-then-401 state with only `ledgerly-api` monitored — a single-monitor setup
   masks it, since `ledgerly-api`'s own health check never touches `ledgerly-ai`. Add alert
   contacts (email) on both monitors as wanted.

## 5. Vercel (`web`)

1. Vercel dashboard → **Add New → Project** → import the `ledgerly` GitHub repo.
2. **Root Directory**: set to `apps/web` during import (the repo isn't a Turborepo, so this
   is a plain dashboard setting, not a `vercel.json` field).
3. Environment variables — Vercel's `.env` paste-in detects keys from `apps/web/.env.example`
   but only 6 are relevant in production (the rest are Sentry build-plugin internals not
   needed here). Each is entered **twice**: once with environment scope **Production and
   Preview**, once with scope **Development** — Vercel has no single scope covering all
   three.

   | Variable | Value |
   |---|---|
   | `API_URL` | `ledgerly-api`'s Render URL (server-side calls from `web`'s route handlers/Server Actions) |
   | `NEXT_PUBLIC_API_URL` | Same Render URL (browser-side calls) |
   | `NEXT_PUBLIC_AI_URL` | `ledgerly-ai`'s Render URL (browser-side `/health` page checks `ai` directly) |
   | `NEXT_PUBLIC_SITE_URL` | The Vercel production domain itself, e.g. `https://ledgerly-ruby-two.vercel.app` — needed because the OG image route builds an absolute URL |
   | `NEXT_PUBLIC_SENTRY_DSN` | §2, the `web`-project DSN |
   | `NEXT_PUBLIC_SENTRY_ENVIRONMENT` | `production` |

   All six are **Config** type (plain, readable), not **Secret** — every `NEXT_PUBLIC_*`
   var is inlined into the client bundle regardless, and `API_URL` carries no credential.
   (If a var was accidentally saved as Secret, Vercel won't let it convert to Config in
   place — delete the row and re-add it as Config.)
4. Deploy. `NEXT_PUBLIC_*` values are inlined at **build time** — changing one later
   requires a redeploy (with build cache cleared) to take effect, not just a settings save.
5. Once deployed, copy the production domain and go back to §3.2/§3.3 to fill in
   `CORS_ALLOWED_ORIGINS` and `AI_CORS_ORIGINS` on Render, then redeploy both Render
   services.

## 6. Order of operations, start to finish

1. §1 Cloudflare R2 bucket + API token.
2. §2 Sentry projects (or skip — `SENTRY_DSN`/`AI_SENTRY_DSN`/`NEXT_PUBLIC_SENTRY_DSN` can
   stay blank; error tracking is inactive but nothing breaks).
3. §3 Render blueprint apply, leaving `AI_BASE_URL`/`CORS_ALLOWED_ORIGINS`/
   `AI_CORS_ORIGINS` blank.
4. §5 Vercel import + deploy, using the two Render URLs from step 3 for `API_URL` /
   `NEXT_PUBLIC_API_URL` / `NEXT_PUBLIC_AI_URL`.
5. Back to Render: fill in `AI_BASE_URL` (ai's URL), `CORS_ALLOWED_ORIGINS` and
   `AI_CORS_ORIGINS` (the Vercel domain) on both services, redeploy.
6. §4 UptimeRobot monitors on both `ledgerly-api` and `ledgerly-ai`.
7. Smoke test: open the Vercel domain, sign up, upload a document, check `/health` shows
   both `api` and `ai` up.
