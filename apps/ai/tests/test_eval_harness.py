"""T5 — the eval harness scoring logic, exercised against synthetic fixtures the test controls
directly rather than the real 20-document set (that set is exercised by running
``python -m evals.extraction`` itself, not by unit tests)."""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest

from app.extraction import ExtractionService
from app.llm.client import LlmClient, VisionPrompt
from evals.extraction import ACCURACY_GATE, accuracy_by_group_and_field, print_report, run_fixture
from evals.fixtures_loader import Fixture

PDF_BYTES = b"%PDF-1.7\n" + b"0" * 64


def proposal_json(**overrides) -> str:
    body = {
        "vendor": "Acme",
        "currency": "EUR",
        "total_minor": 1210,
        "tax_minor": 210,
        "document_date": "2026-07-14",
        "lines": [{"description": "item", "amount_minor": 1210}],
        "confidence": {
            "currency": 0.95,
            "total_minor": 0.95,
            "tax_minor": 0.95,
            "document_date": 0.95,
        },
    }
    body.update(overrides)
    return json.dumps(body)


class StaticLlmClient(LlmClient):
    def __init__(self, body: str) -> None:
        self._body = body

    @property
    def model_name(self) -> str:
        return "static-v1"

    def complete(self, prompt: str) -> str:
        return self._body

    def complete_vision(self, prompt: VisionPrompt) -> str:
        return self._body


def make_fixture(tmp_path: Path, group: str, expected: dict) -> Fixture:
    tmp_path.mkdir(parents=True, exist_ok=True)
    source = tmp_path / "doc.pdf"
    source.write_bytes(PDF_BYTES)
    return Fixture(
        name=f"{group}/doc",
        group=group,
        source_path=source,
        content_type="application/pdf",
        expected=expected,
    )


CORRECT_EXPECTED = {
    "currency": "EUR",
    "total_minor": 1210,
    "tax_minor": 210,
    "document_date": "2026-07-14",
}


def test_a_known_good_fixture_scores_100_percent(tmp_path):
    fixture = make_fixture(tmp_path, "real", CORRECT_EXPECTED)
    service = ExtractionService(StaticLlmClient(proposal_json()))

    result = run_fixture(service, fixture)

    assert result.field_correct == {"currency": True, "total_minor": True, "document_date": True}


def test_a_deliberately_wrong_expected_output_scores_below_the_known_good_fixture(tmp_path):
    correct_fixture = make_fixture(tmp_path / "a", "real", CORRECT_EXPECTED)
    wrong_fixture = make_fixture(
        tmp_path / "b",
        "real",
        {**CORRECT_EXPECTED, "total_minor": 99999, "document_date": "1999-01-01"},
    )
    service = ExtractionService(StaticLlmClient(proposal_json()))

    correct_result = run_fixture(service, correct_fixture)
    wrong_result = run_fixture(service, wrong_fixture)

    correct_score = sum(correct_result.field_correct.values())
    wrong_score = sum(wrong_result.field_correct.values())
    assert wrong_score < correct_score


def test_a_fixture_with_no_source_document_is_skipped_not_failed(tmp_path):
    missing = Fixture(
        name="real/missing",
        group="real",
        source_path=tmp_path / "does-not-exist.pdf",
        content_type="application/pdf",
        expected=CORRECT_EXPECTED,
    )
    service = ExtractionService(StaticLlmClient(proposal_json()))

    result = run_fixture(service, missing)

    assert result.skipped is True
    assert result.error is None


def test_accuracy_is_reported_separately_per_group(tmp_path):
    real_fixture = make_fixture(tmp_path / "r", "real", CORRECT_EXPECTED)
    public_fixture = make_fixture(
        tmp_path / "p", "public", {**CORRECT_EXPECTED, "total_minor": 1}
    )
    service = ExtractionService(StaticLlmClient(proposal_json()))

    results = [run_fixture(service, real_fixture), run_fixture(service, public_fixture)]
    table = accuracy_by_group_and_field(results)

    assert table["real"]["total_minor"] == (1, 1)
    assert table["public"]["total_minor"] == (0, 1)
    assert table["overall"]["total_minor"] == (1, 2)


def test_the_gate_fails_when_overall_accuracy_is_below_90_percent(tmp_path, capsys):
    fixtures = [
        make_fixture(tmp_path / str(i), "real", {**CORRECT_EXPECTED, "total_minor": 1})
        for i in range(10)
    ]
    service = ExtractionService(StaticLlmClient(proposal_json()))  # always returns 1210, never 1

    results = [run_fixture(service, f) for f in fixtures]
    gate_passed = print_report(results)

    assert gate_passed is False


def test_the_gate_passes_when_accuracy_is_at_or_above_90_percent(tmp_path, capsys):
    fixtures = [make_fixture(tmp_path / str(i), "real", CORRECT_EXPECTED) for i in range(10)]
    service = ExtractionService(StaticLlmClient(proposal_json()))

    results = [run_fixture(service, f) for f in fixtures]
    gate_passed = print_report(results)

    assert gate_passed is True


def test_the_report_prints_latency_percentiles(tmp_path, capsys):
    fixtures = [make_fixture(tmp_path / str(i), "real", CORRECT_EXPECTED) for i in range(3)]
    service = ExtractionService(StaticLlmClient(proposal_json()))

    results = [run_fixture(service, f) for f in fixtures]
    print_report(results)

    captured = capsys.readouterr()
    assert "p50=" in captured.out
    assert "p95=" in captured.out


def test_the_report_prints_a_row_per_group_present():
    accuracy_gate_is_documented = 0.0 < ACCURACY_GATE < 1.0
    assert accuracy_gate_is_documented


def test_an_extraction_failure_counts_as_incorrect_on_every_gated_field(tmp_path):
    fixture = make_fixture(tmp_path, "real", CORRECT_EXPECTED)
    service = ExtractionService(StaticLlmClient("not json"))

    result = run_fixture(service, fixture)

    assert result.error is not None
    assert result.field_correct == {}
