"""T4 — per-field confidence from the model.

The schema (``docs/contracts/extraction-proposal.schema.json``) is the actual enforcement point:
confidence is required for currency/total_minor/tax_minor/document_date, and each score is
constrained to [0,1]. These tests prove `ExtractionService` inherits that enforcement rather than
smoothing over it — a missing or out-of-range score must fail the whole extraction, never get
silently defaulted or clamped.
"""

from __future__ import annotations

import json
import uuid

import pytest

from app.extraction import ExtractionFailedError, ExtractionService
from app.llm.client import LlmClient, VisionPrompt

PDF_BYTES = b"%PDF-1.7\n" + b"0" * 64


def make_body(confidence: dict) -> str:
    return json.dumps(
        {
            "vendor": "Acme",
            "currency": "EUR",
            "total_minor": 1210,
            "tax_minor": 210,
            "document_date": "2026-07-14",
            "lines": [],
            "confidence": confidence,
        }
    )


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


FULL_CONFIDENCE = {
    "vendor": 0.8,
    "currency": 0.91,
    "total_minor": 0.77,
    "tax_minor": 0.85,
    "document_date": 0.93,
}


def extract_with(confidence: dict) -> dict:
    service = ExtractionService(StaticLlmClient(make_body(confidence)))
    return service.extract(str(uuid.uuid4()), PDF_BYTES, "application/pdf")


def test_every_gated_field_carries_the_models_own_confidence_value():
    proposal = extract_with(FULL_CONFIDENCE)

    for field, expected in FULL_CONFIDENCE.items():
        assert proposal["confidence"][field] == expected


@pytest.mark.parametrize("missing_field", ["currency", "total_minor", "tax_minor", "document_date"])
def test_a_missing_confidence_on_a_gated_field_fails_closed(missing_field):
    confidence = {k: v for k, v in FULL_CONFIDENCE.items() if k != missing_field}

    with pytest.raises(ExtractionFailedError):
        extract_with(confidence)


def test_missing_vendor_confidence_is_allowed_vendor_is_not_gated():
    confidence = {k: v for k, v in FULL_CONFIDENCE.items() if k != "vendor"}

    proposal = extract_with(confidence)

    assert "vendor" not in proposal["confidence"]


@pytest.mark.parametrize("bad_value", [-0.1, 1.1, 2.0, -1.0])
def test_a_confidence_outside_0_1_fails_the_extraction(bad_value):
    confidence = {**FULL_CONFIDENCE, "total_minor": bad_value}

    with pytest.raises(ExtractionFailedError):
        extract_with(confidence)


def test_boundary_confidence_values_zero_and_one_are_accepted():
    confidence = {**FULL_CONFIDENCE, "total_minor": 0.0, "tax_minor": 1.0}

    proposal = extract_with(confidence)

    assert proposal["confidence"]["total_minor"] == 0.0
    assert proposal["confidence"]["tax_minor"] == 1.0


def test_nothing_defaults_a_missing_score_to_full_confidence():
    """A model that simply forgets a score must not have that read as certainty."""
    confidence = {k: v for k, v in FULL_CONFIDENCE.items() if k != "document_date"}

    with pytest.raises(ExtractionFailedError):
        extract_with(confidence)
