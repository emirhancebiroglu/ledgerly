"""The M5 eval gate: ``python -m evals.extraction``.

Runs every fixture in ``evals/fixtures/{real,public,synthetic}`` through the configured
``ExtractionService``, scores per-field accuracy on the three fields the milestone gates on
(currency, total_minor, document_date), and prints p50/p95 latency. Exits non-zero if any group's
accuracy on a gated field falls below the 90% threshold — that is what makes this a gate rather
than a report.

A document under ``real/`` whose source PDF is missing (``invoices/`` is gitignored and may not
exist on a given machine) is skipped, not failed: the eval must still run to completion for anyone
without the private fixture set, scored only on the fixtures actually present.
"""

from __future__ import annotations

import os
import statistics
import sys
import time
from dataclasses import dataclass, field

from app.extraction import ExtractionFailedError, ExtractionService
from app.main import get_llm_client as build_llm_client
from evals.fixtures_loader import GROUPS, Fixture, load_fixtures

GATED_FIELDS = ("currency", "total_minor", "document_date")
ACCURACY_GATE = 0.90


@dataclass
class FixtureResult:
    fixture: Fixture
    skipped: bool = False
    error: str | None = None
    field_correct: dict[str, bool] = field(default_factory=dict)
    latency_seconds: float | None = None


def run_fixture(service: ExtractionService, fixture: Fixture) -> FixtureResult:
    if not fixture.source_available:
        return FixtureResult(fixture=fixture, skipped=True)

    content = fixture.read_bytes()
    start = time.monotonic()
    try:
        proposal = service.extract("00000000-0000-0000-0000-000000000000", content, fixture.content_type)
    except ExtractionFailedError as error:
        return FixtureResult(fixture=fixture, error=str(error))
    latency = time.monotonic() - start

    field_correct = {
        gated_field: proposal.get(gated_field) == fixture.expected.get(gated_field)
        for gated_field in GATED_FIELDS
    }
    return FixtureResult(fixture=fixture, field_correct=field_correct, latency_seconds=latency)


def accuracy_by_group_and_field(
    results: list[FixtureResult],
) -> dict[str, dict[str, tuple[int, int]]]:
    """Returns {group: {field: (correct, total)}}, skipping fixtures with no source available."""
    scored = [r for r in results if not r.skipped]
    table: dict[str, dict[str, tuple[int, int]]] = {}
    for group in (*GROUPS, "overall"):
        group_results = scored if group == "overall" else [r for r in scored if r.fixture.group == group]
        table[group] = {}
        for gated_field in GATED_FIELDS:
            correct = sum(1 for r in group_results if r.field_correct.get(gated_field))
            total = len(group_results)
            table[group][gated_field] = (correct, total)
    return table


def print_report(results: list[FixtureResult]) -> bool:
    """Prints the accuracy table and latency, returns True if the gate passes."""
    skipped = [r for r in results if r.skipped]
    scored = [r for r in results if not r.skipped]

    if skipped:
        print(f"Skipped {len(skipped)} fixture(s) with no source document available:")
        for r in skipped:
            print(f"  - {r.fixture.name}")
        print()

    table = accuracy_by_group_and_field(results)
    gate_passed = True

    print(f"{'group':<12}", end="")
    for gated_field in GATED_FIELDS:
        print(f"{gated_field:>16}", end="")
    print()

    for group in (*GROUPS, "overall"):
        correct_total = table[group]
        if correct_total[GATED_FIELDS[0]][1] == 0:
            continue
        print(f"{group:<12}", end="")
        for gated_field in GATED_FIELDS:
            correct, total = correct_total[gated_field]
            accuracy = correct / total if total else 0.0
            print(f"{correct}/{total} ({accuracy:>5.0%})".rjust(16), end="")
            if group == "overall" and accuracy < ACCURACY_GATE:
                gate_passed = False
        print()

    errors = [r for r in scored if r.error]
    if errors:
        print(f"\n{len(errors)} fixture(s) failed extraction entirely:")
        for r in errors:
            print(f"  - {r.fixture.name}: {r.error}")

    latencies = [r.latency_seconds for r in scored if r.latency_seconds is not None]
    if latencies:
        p50 = statistics.median(latencies)
        p95 = statistics.quantiles(latencies, n=20)[18] if len(latencies) >= 2 else latencies[0]
        print(f"\nLatency: p50={p50:.2f}s p95={p95:.2f}s over {len(latencies)} calls")

    return gate_passed


def main() -> int:
    # A free-tier provider key is rate-limited well below what 20 back-to-back calls need; a paid
    # tier needs none of this. Opt-in via env rather than hardcoded, since the harness itself must
    # stay fast for CI and for the fake-provider unit tests.
    pace_seconds = float(os.environ.get("EVAL_PACE_SECONDS", "0"))

    fixtures = load_fixtures()
    service = ExtractionService(build_llm_client())
    results = []
    for index, fixture in enumerate(fixtures):
        if index > 0 and pace_seconds > 0:
            time.sleep(pace_seconds)
        results.append(run_fixture(service, fixture))
    gate_passed = print_report(results)
    return 0 if gate_passed else 1


if __name__ == "__main__":
    sys.exit(main())
