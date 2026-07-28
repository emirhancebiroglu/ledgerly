#!/usr/bin/env bash
set -euo pipefail

# Proves the extraction queue keeps an upload durable while the ai service is unavailable, then
# lets the same row finish after ai returns. Run against a locally running compose stack:
#   PDF_PATH=./invoice.pdf bash scripts/degradation-smoke.sh

API_URL="${API_URL:-http://localhost:8080}"
COMPOSE_FILE="${COMPOSE_FILE:-infra/docker-compose.yml}"
PDF_PATH="${PDF_PATH:?Set PDF_PATH to a valid PDF fixture}"
WAIT_SECONDS="${WAIT_SECONDS:-300}"
# Defaults cover the 5s queue poll interval plus the first refused agent call. Raise this if the
# local queue interval is overridden.
OUTAGE_SETTLE_SECONDS="${OUTAGE_SETTLE_SECONDS:-8}"

command -v curl >/dev/null
command -v jq >/dev/null
test -f "$PDF_PATH"

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

status_of() {
  curl -fsS "$API_URL/api/v1/documents/$1" -H "Authorization: Bearer $TOKEN" | jq -er '.status'
}

wait_for_status() {
  local document_id="$1"
  local wanted="$2"
  local deadline=$((SECONDS + WAIT_SECONDS))
  local current
  while (( SECONDS < deadline )); do
    current="$(status_of "$document_id")"
    if [[ "$current" == "$wanted" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for document $document_id to become $wanted; last status: $current" >&2
  return 1
}

wait_for_terminal_outcome() {
  local document_id="$1"
  local deadline=$((SECONDS + WAIT_SECONDS))
  local current
  while (( SECONDS < deadline )); do
    current="$(status_of "$document_id")"
    case "$current" in
      EXTRACTED|NEEDS_REVIEW) return 0 ;;
      FAILED)
        echo "Document $document_id failed after ai recovery" >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "Timed out waiting for document $document_id to finish; last status: $current" >&2
  return 1
}

AI_WAS_RUNNING=false
AI_STARTED_FOR_RECOVERY=false
if [[ -n "$(compose ps --status running -q ai)" ]]; then
  AI_WAS_RUNNING=true
fi

restore_ai() {
  if [[ "$AI_WAS_RUNNING" == true && "$AI_STARTED_FOR_RECOVERY" == false ]]; then
    compose start ai >/dev/null
  elif [[ "$AI_WAS_RUNNING" == false && "$AI_STARTED_FOR_RECOVERY" == true ]]; then
    compose stop ai >/dev/null
  fi
}
trap restore_ai EXIT

curl -fsS "$API_URL/actuator/health" >/dev/null
compose stop ai >/dev/null

suffix="$(date +%s)-$$"
register_response="$(
  curl -fsS -X POST "$API_URL/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    --data "{\"organizationName\":\"degradation-$suffix\",\"email\":\"degradation-$suffix@example.test\",\"password\":\"correct-horse-battery\"}"
)"
TOKEN="$(jq -er '.access_token' <<<"$register_response")"

upload_response="$(
  curl -fsS -X POST "$API_URL/api/v1/documents" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: degradation-$suffix" \
    -F "file=@$PDF_PATH;type=application/pdf"
)"
document_id="$(jq -er '.id' <<<"$upload_response")"
initial_status="$(jq -er '.status' <<<"$upload_response")"
[[ "$initial_status" == "PENDING" ]]

# The response is necessarily PENDING before the first poll. Let a stopped-ai dispatch actually
# happen, then verify the row is still durable instead of merely reading the initial response.
sleep "$OUTAGE_SETTLE_SECONDS"
wait_for_status "$document_id" PENDING
compose start ai >/dev/null
AI_STARTED_FOR_RECOVERY=true
wait_for_terminal_outcome "$document_id"

echo "PASS: $document_id stayed durable during ai outage and completed after recovery."
