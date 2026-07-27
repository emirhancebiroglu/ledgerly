"""The M4 stub implementation of the ``LlmClient`` port."""

import json

import pytest

from app.llm import FakeLlmClient
from app.llm.client import LlmClient, LlmError, VisionPrompt

PDF_BYTES = b"%PDF-1.7\n" + b"0" * 512


def vision_prompt(content: bytes = PDF_BYTES) -> VisionPrompt:
    return VisionPrompt(
        instruction="extract this", content=content, content_type="application/pdf"
    )


def test_the_stub_implements_the_port():
    assert isinstance(FakeLlmClient(), LlmClient)


def test_it_reports_a_model_name_so_output_is_traceable():
    assert FakeLlmClient().model_name == "fake-llm-v1"


def test_it_returns_json_whose_arithmetic_is_internally_consistent():
    body = json.loads(FakeLlmClient().complete_vision(vision_prompt()))

    line_sum = sum(line["amount_minor"] for line in body["lines"])
    assert body["total_minor"] == line_sum + body["tax_minor"]


def test_identical_bytes_yield_identical_output():
    stub = FakeLlmClient()

    assert stub.complete_vision(vision_prompt()) == stub.complete_vision(vision_prompt())


def test_different_bytes_yield_different_output():
    stub = FakeLlmClient()

    first = stub.complete_vision(vision_prompt(PDF_BYTES))
    second = stub.complete_vision(vision_prompt(PDF_BYTES + b"different"))

    assert first != second


def test_content_too_small_to_read_raises_rather_than_inventing_an_answer():
    with pytest.raises(LlmError):
        FakeLlmClient().complete_vision(vision_prompt(b"tiny"))


def test_empty_content_raises():
    with pytest.raises(LlmError):
        FakeLlmClient().complete_vision(vision_prompt(b""))


def test_it_marks_its_own_output_as_stub_generated():
    body = json.loads(FakeLlmClient().complete_vision(vision_prompt()))

    assert any("stub" in warning.lower() for warning in body["warnings"])
