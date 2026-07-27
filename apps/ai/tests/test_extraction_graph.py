"""T3 — the vision -> structured-output extraction graph.

A scripted fake ``LlmClient`` drives every scenario; nothing here calls a real model.
"""

from __future__ import annotations

import json

import pytest

from app.llm.client import LlmClient, LlmError, VisionPrompt
from app.llm.extraction_graph import (
    CONFIDENCE_THRESHOLD,
    ExtractionFailedError,
    run_extraction_graph,
)

PDF_BYTES = b"%PDF-1.7\n" + b"0" * 64


def proposal(**overrides) -> str:
    body = {
        "vendor": "Acme",
        "currency": "EUR",
        "total_minor": 1210,
        "tax_minor": 210,
        "document_date": "2026-07-14",
        "lines": [],
        "confidence": {
            "vendor": 0.9,
            "currency": 0.95,
            "total_minor": 0.95,
            "tax_minor": 0.95,
            "document_date": 0.95,
        },
    }
    body.update(overrides)
    return json.dumps(body)


class ScriptedLlmClient(LlmClient):
    def __init__(self, responses: list) -> None:
        self._responses = list(responses)
        self.call_count = 0

    @property
    def model_name(self) -> str:
        return "scripted-v1"

    def complete(self, prompt: str) -> str:
        return self._next()

    def complete_vision(self, prompt: VisionPrompt) -> str:
        return self._next()

    def _next(self):
        self.call_count += 1
        outcome = self._responses.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def run(client, instruction="extract"):
    return run_extraction_graph(client, instruction, PDF_BYTES, "application/pdf")


def test_high_confidence_extraction_does_not_trigger_a_self_check():
    client = ScriptedLlmClient([proposal()])

    result = run(client)

    assert client.call_count == 1
    assert result["self_checked_fields"] == []
    assert result["extracted"]["total_minor"] == 1210


def test_a_field_below_threshold_triggers_exactly_one_self_check_pass():
    low_confidence = proposal(confidence={
        "vendor": 0.9,
        "currency": 0.95,
        "total_minor": 0.3,  # below threshold
        "tax_minor": 0.95,
        "document_date": 0.95,
    })
    client = ScriptedLlmClient([low_confidence, proposal(total_minor=1210)])

    result = run(client)

    assert client.call_count == 2  # extract + exactly one self-check, never more
    assert result["self_checked_fields"] == ["total_minor"]


def test_the_self_check_can_correct_a_wrong_value_and_records_that_it_did():
    wrong = proposal(
        total_minor=9999,
        confidence={
            "vendor": 0.9,
            "currency": 0.95,
            "total_minor": 0.2,
            "tax_minor": 0.95,
            "document_date": 0.95,
        },
    )
    corrected = proposal(total_minor=1210, confidence={
        "vendor": 0.9,
        "currency": 0.95,
        "total_minor": 0.9,
        "tax_minor": 0.95,
        "document_date": 0.95,
    })
    client = ScriptedLlmClient([wrong, corrected])

    result = run(client)

    assert result["extracted"]["total_minor"] == 1210
    assert "total_minor" in result["self_checked_fields"]


def test_a_second_low_confidence_answer_after_self_check_does_not_trigger_another_pass():
    """The self-check node runs at most once — even if its own output is still low-confidence,
    the graph must not loop back into self_check again."""
    still_low = proposal(confidence={
        "vendor": 0.9,
        "currency": 0.95,
        "total_minor": 0.1,
        "tax_minor": 0.95,
        "document_date": 0.95,
    })
    client = ScriptedLlmClient([still_low, still_low])

    result = run(client)

    assert client.call_count == 2  # not 3, not unbounded
    assert result["self_checked_fields"] == ["total_minor"]


def test_multiple_gated_fields_below_threshold_still_trigger_only_one_self_check_call():
    low = proposal(confidence={
        "vendor": 0.9,
        "currency": 0.4,
        "total_minor": 0.3,
        "tax_minor": 0.95,
        "document_date": 0.2,
    })
    client = ScriptedLlmClient([low, proposal()])

    result = run(client)

    assert client.call_count == 2
    assert set(result["self_checked_fields"]) == {"currency", "total_minor", "document_date"}


def test_unparseable_json_from_the_first_call_raises_extraction_failed():
    client = ScriptedLlmClient(["not json at all"])

    with pytest.raises(ExtractionFailedError):
        run(client)


def test_a_model_error_on_the_first_call_raises_extraction_failed():
    client = ScriptedLlmClient([LlmError("upstream down")])

    with pytest.raises(ExtractionFailedError):
        run(client)


def test_unparseable_json_from_the_self_check_keeps_the_original_extraction():
    low = proposal(confidence={
        "vendor": 0.9,
        "currency": 0.95,
        "total_minor": 0.1,
        "tax_minor": 0.95,
        "document_date": 0.95,
    })
    client = ScriptedLlmClient([low, "not json"])

    result = run(client)

    assert result["extracted"]["total_minor"] == 1210  # original value preserved
    assert result["self_checked_fields"] == ["total_minor"]


def test_a_model_error_on_the_self_check_keeps_the_original_extraction():
    low = proposal(confidence={
        "vendor": 0.9,
        "currency": 0.95,
        "total_minor": 0.1,
        "tax_minor": 0.95,
        "document_date": 0.95,
    })
    client = ScriptedLlmClient([low, LlmError("self-check call failed")])

    result = run(client)

    assert result["extracted"]["total_minor"] == 1210
    assert result["self_checked_fields"] == ["total_minor"]


def test_confidence_threshold_is_a_module_constant_not_a_magic_number():
    assert 0.0 < CONFIDENCE_THRESHOLD < 1.0
