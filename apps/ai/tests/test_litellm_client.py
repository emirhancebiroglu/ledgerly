"""T1 — the real ``LlmClient`` adapter, backed by LiteLLM.

No test here calls the network: ``litellm.completion`` is monkeypatched. What is under test is the
adapter's contract — port errors normalized, bytes/content-type forwarded, model name traceable —
not LiteLLM's own correctness.
"""

from __future__ import annotations

import base64
from pathlib import Path

import litellm
import pytest
from litellm.exceptions import APIError, BadRequestError, RateLimitError

from app.llm.client import LlmClient, LlmError, VisionPrompt
from app.llm.litellm_client import LiteLlmClient
from app.llm.resilient import NonRetryableLlmError, RetryableLlmError

PDF_BYTES = b"%PDF-1.7\n" + b"0" * 512

# A real, renderable PDF — the fake PDF_BYTES above isn't valid enough for pypdfium2 to open.
REAL_PDF_BYTES = (
    Path(__file__).resolve().parents[1] / "evals" / "fixtures" / "synthetic" / "02_no_itemisation.pdf"
).read_bytes()


def make_response(text: str):
    class Message:
        content = text

    class Choice:
        message = Message()

    class Response:
        choices = [Choice()]

    return Response()


def test_it_implements_the_port():
    assert isinstance(LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5), LlmClient)


def test_model_name_reports_the_configured_model():
    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)

    assert client.model_name == "gemini/gemini-3.6-flash"


def test_complete_vision_sends_the_detected_content_type_and_bytes(monkeypatch):
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("model said hi")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)
    result = client.complete_vision(
        VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
    )

    assert result == "model said hi"
    sent_content = captured["messages"][0]["content"]
    file_part = next(part for part in sent_content if part["type"] == "file")
    encoded = base64.b64encode(PDF_BYTES).decode("ascii")
    assert file_part["file"]["file_data"] == f"data:application/pdf;base64,{encoded}"


def test_complete_vision_never_trusts_a_claimed_content_type(monkeypatch):
    """The content type sent is whatever the caller (api's magic-byte detector) determined —
    this adapter has no opinion, it just forwards what it was given."""
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)
    client.complete_vision(
        VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="image/png")
    )

    sent_content = captured["messages"][0]["content"]
    image_part = next(part for part in sent_content if part["type"] == "image_url")
    assert image_part["image_url"]["url"].startswith("data:image/png;base64,")


def test_a_provider_error_surfaces_as_llm_error_not_a_provider_exception(monkeypatch):
    def raising_completion(**kwargs):
        raise APIError(
            status_code=500, message="boom", llm_provider="gemini", model="gemini/gemini-3.6-flash"
        )

    monkeypatch.setattr(litellm, "completion", raising_completion)

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)

    with pytest.raises(LlmError):
        client.complete_vision(
            VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
        )


def test_a_bad_request_error_surfaces_as_non_retryable_not_a_provider_exception(monkeypatch):
    """litellm.exceptions.BadRequestError does NOT share a base with litellm.exceptions.APIError
    (they're siblings, both wrapping different openai.* exceptions) — catching the latter alone
    would silently miss every 4xx a provider like this returns."""

    def raising_completion(**kwargs):
        raise BadRequestError(
            message="invalid request",
            llm_provider="openai",
            model="kimi-k3",
            response=_fake_httpx_response(400),
        )

    monkeypatch.setattr(litellm, "completion", raising_completion)

    client = LiteLlmClient(model="kimi-k3", api_key="k", timeout_seconds=5)

    with pytest.raises(NonRetryableLlmError):
        client.complete_vision(
            VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
        )


def test_a_5xx_api_status_error_is_retryable(monkeypatch):
    def raising_completion(**kwargs):
        raise litellm.exceptions.InternalServerError(
            message="upstream broke",
            llm_provider="openai",
            model="kimi-k3",
            response=_fake_httpx_response(500),
        )

    monkeypatch.setattr(litellm, "completion", raising_completion)

    client = LiteLlmClient(model="kimi-k3", api_key="k", timeout_seconds=5)

    with pytest.raises(RetryableLlmError):
        client.complete_vision(
            VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
        )


def _fake_httpx_response(status_code: int):
    import httpx

    return httpx.Response(status_code=status_code, request=httpx.Request("POST", "https://example.com"))


def test_a_rate_limit_error_surfaces_as_llm_error(monkeypatch):
    def raising_completion(**kwargs):
        raise RateLimitError(
            message="slow down", llm_provider="gemini", model="gemini/gemini-3.6-flash"
        )

    monkeypatch.setattr(litellm, "completion", raising_completion)

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)

    with pytest.raises(LlmError):
        client.complete_vision(
            VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
        )


def test_an_empty_model_response_raises_llm_error_rather_than_returning_blank(monkeypatch):
    monkeypatch.setattr(litellm, "completion", lambda **kwargs: make_response(""))

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5)

    with pytest.raises(LlmError):
        client.complete_vision(
            VisionPrompt(instruction="extract", content=PDF_BYTES, content_type="application/pdf")
        )


def test_the_configured_timeout_and_api_key_are_forwarded_to_litellm(monkeypatch):
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(model="gemini/gemini-3.6-flash", api_key="secret-key", timeout_seconds=12.5)
    client.complete("hello")

    assert captured["api_key"] == "secret-key"
    assert captured["timeout"] == 12.5
    assert captured["model"] == "gemini/gemini-3.6-flash"


def test_a_custom_api_base_is_forwarded_to_litellm(monkeypatch):
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(
        model="openai/mimo-v2.5",
        api_key="k",
        timeout_seconds=5,
        api_base="https://opencode.ai/zen/go/v1",
    )
    client.complete("hello")

    assert captured["api_base"] == "https://opencode.ai/zen/go/v1"


def test_a_pdf_is_sent_natively_when_the_provider_supports_it(monkeypatch):
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(
        model="gemini/gemini-3.6-flash", api_key="k", timeout_seconds=5, supports_native_pdf=True
    )
    client.complete_vision(
        VisionPrompt(instruction="extract", content=REAL_PDF_BYTES, content_type="application/pdf")
    )

    sent_content = captured["messages"][0]["content"]
    assert any(part["type"] == "file" for part in sent_content)


def test_a_pdf_is_rendered_to_images_when_the_provider_does_not_support_native_pdf(monkeypatch):
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(
        model="openai/mimo-v2.5",
        api_key="k",
        timeout_seconds=5,
        api_base="https://opencode.ai/zen/go/v1",
        supports_native_pdf=False,
    )
    client.complete_vision(
        VisionPrompt(instruction="extract", content=REAL_PDF_BYTES, content_type="application/pdf")
    )

    sent_content = captured["messages"][0]["content"]
    assert not any(part["type"] == "file" for part in sent_content)
    image_parts = [part for part in sent_content if part["type"] == "image_url"]
    assert len(image_parts) >= 1
    assert image_parts[0]["image_url"]["url"].startswith("data:image/png;base64,")


def test_a_non_pdf_image_is_never_rendered_even_when_native_pdf_is_disabled(monkeypatch):
    """The rendering path only applies to PDFs — an image is already an image."""
    captured = {}

    def fake_completion(**kwargs):
        captured.update(kwargs)
        return make_response("ok")

    monkeypatch.setattr(litellm, "completion", fake_completion)

    client = LiteLlmClient(
        model="openai/mimo-v2.5",
        api_key="k",
        timeout_seconds=5,
        api_base="https://opencode.ai/zen/go/v1",
        supports_native_pdf=False,
    )
    original_bytes = b"\x89PNG\r\n\x1a\n" + b"0" * 64
    client.complete_vision(
        VisionPrompt(instruction="extract", content=original_bytes, content_type="image/png")
    )

    sent_content = captured["messages"][0]["content"]
    image_part = next(part for part in sent_content if part["type"] == "image_url")
    encoded = base64.b64encode(original_bytes).decode("ascii")
    assert image_part["image_url"]["url"] == f"data:image/png;base64,{encoded}"
