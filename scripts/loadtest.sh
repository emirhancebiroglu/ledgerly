#!/usr/bin/env bash
set -euo pipefail

# Deterministic local API gate. It starts the compose stack with fake AI adapters, uploads 200
# small PDFs at 20-way concurrency, and fails on an unexpected response or p95 over two seconds.
API_URL="${API_URL:-http://localhost:8080}"
TOTAL="${TOTAL:-200}"
CONCURRENCY="${CONCURRENCY:-20}"
P95_LIMIT_MS="${P95_LIMIT_MS:-2000}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-90}"
COMPOSE_FILE="${COMPOSE_FILE:-infra/docker-compose.yml}"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env}"
export AI_LLM_PROVIDER=fake AI_EMBEDDING_PROVIDER=fake LOADTEST_DOCUMENT_QUOTA="${LOADTEST_DOCUMENT_QUOTA:-1000}"
export LOADTEST_AI_QUOTA="${LOADTEST_AI_QUOTA:-1000}"
export AI_LLM_API_KEY="${AI_LLM_API_KEY:-fake}" AI_EMBEDDING_API_KEY="${AI_EMBEDDING_API_KEY:-fake}"

command -v curl >/dev/null
if command -v python >/dev/null; then
  python_command=python
elif command -v python.exe >/dev/null; then
  python_command=python.exe
else
  echo "FAIL: Python 3 is required" >&2
  exit 1
fi

# Local only: compose needs the shared internal credential even when the developer's .env keeps
# deployment secrets out of the repository. A caller-supplied value remains untouched.
export AI_SERVICE_TOKEN="${AI_SERVICE_TOKEN:-$($python_command -c 'import secrets; print(secrets.token_urlsafe(32))')}"

[[ -f "$COMPOSE_ENV_FILE" ]] || { echo "FAIL: compose environment file not found: $COMPOSE_ENV_FILE" >&2; exit 1; }
compose() { docker compose --env-file "$COMPOSE_ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
pdf="$tmp_dir/invoice.pdf"
printf '%%PDF-1.7\n%s\n%%%%EOF\n' "$(head -c 512 /dev/zero | tr '\0' '0')" > "$pdf"
curl_pdf="$pdf"
if command -v cygpath >/dev/null; then
  curl_pdf="$(cygpath -w "$pdf")"
fi

compose up -d postgres redis ai api
startup_deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
until curl -fsS "$API_URL/actuator/health" >/dev/null; do
  if (( SECONDS >= startup_deadline )); then
    echo "FAIL: API did not become healthy within ${STARTUP_TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  sleep 1
done
suffix="$(date +%s)-$$"
token="$(curl -fsS -X POST "$API_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  --data "{\"organizationName\":\"loadtest-$suffix\",\"email\":\"loadtest-$suffix@example.test\",\"password\":\"correct-horse-battery\"}" \
  | "$python_command" -c 'import json, sys; print(json.load(sys.stdin)["accessToken"])')"

upload_one() {
  local n="$1" out status elapsed
  out="$(curl -sS -o "$tmp_dir/$n.body" -w '%{http_code} %{time_total}' -X POST "$API_URL/api/v1/documents" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: loadtest-$suffix-$n" \
    -F "file=@$curl_pdf;type=application/pdf" || printf '000 0')"
  status="${out%% *}"; elapsed="${out##* }"
  printf '%s %s\n' "$status" "$elapsed" > "$tmp_dir/$n.result"
}

active=0
for n in $(seq 1 "$TOTAL"); do
  upload_one "$n" &
  active=$((active + 1))
  if (( active >= CONCURRENCY )); then
    wait -n || true
    active=$((active - 1))
  fi
done
while (( active > 0 )); do
  wait -n || true
  active=$((active - 1))
done

"$python_command" - "$tmp_dir" "$TOTAL" "$P95_LIMIT_MS" <<'PY'
import pathlib, sys
root, total, limit = pathlib.Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
rows = [path.read_text().split() for path in root.glob("*.result")]
if len(rows) != total:
    raise SystemExit(f"FAIL: expected {total} results, got {len(rows)}")
errors = [row for row in rows if row[0] != "201"]
latencies = sorted(float(row[1]) * 1000 for row in rows)
p50 = latencies[(len(latencies) - 1) // 2]
p95 = latencies[max(0, int(len(latencies) * .95) - 1)]
error_rate = len(errors) / len(rows) * 100
print(f"requests={len(rows)} errors={len(errors)} error_rate_pct={error_rate:.2f} p50_ms={p50:.1f} p95_ms={p95:.1f}")
if errors or p95 > limit:
    raise SystemExit("FAIL: unexpected HTTP errors or p95 budget exceeded")
PY
