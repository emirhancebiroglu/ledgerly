#!/usr/bin/env bash
set -euo pipefail

# Fails if docker-compose.yml references an env var that .env.example never documents, so a
# fresh clone's .env.example -> .env never silently misses a variable compose actually needs.
#   bash scripts/check-env-example.sh

COMPOSE_FILE="${COMPOSE_FILE:-infra/docker-compose.yml}"
ENV_EXAMPLE_FILE="${ENV_EXAMPLE_FILE:-.env.example}"

test -f "$COMPOSE_FILE"
test -f "$ENV_EXAMPLE_FILE"

referenced=$(grep -oE '\$\{[A-Z_]+' "$COMPOSE_FILE" | sed 's/\${//' | sort -u)
documented=$(grep -oE '^[A-Z_]+=' "$ENV_EXAMPLE_FILE" | sed 's/=//' | sort -u)

missing=$(comm -23 <(echo "$referenced") <(echo "$documented"))

if [ -n "$missing" ]; then
  echo "::error::variable(s) referenced in $COMPOSE_FILE but missing from $ENV_EXAMPLE_FILE:"
  echo "$missing"
  exit 1
fi

echo "OK: every $COMPOSE_FILE env var is documented in $ENV_EXAMPLE_FILE"
