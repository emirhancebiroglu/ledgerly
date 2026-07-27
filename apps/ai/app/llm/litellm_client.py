"""The real ``LlmClient`` adapter, backed by LiteLLM.

LiteLLM is the adapter target, not a provider: one OpenAI-shaped call surface over 100+ backends,
so swapping the underlying model is a config change (``model_name``) rather than a new adapter.
Decided at M5 planning — see ``decisions.md``.
"""

from __future__ import annotations

import base64

import litellm
import openai
from litellm.exceptions import (
    APIConnectionError,
    RateLimitError,
    ServiceUnavailableError,
    Timeout,
)

from app.llm.client import LlmClient, VisionPrompt
from app.llm.pdf_to_images import render_pdf_pages_to_png
from app.llm.resilient import NonRetryableLlmError, RetryableLlmError

# Failures worth retrying: the request never reached a stable answer.
_RETRYABLE_EXCEPTIONS = (Timeout, RateLimitError, ServiceUnavailableError, APIConnectionError)


class LiteLlmClient(LlmClient):
    """Calls a real model through LiteLLM's chat-completions interface."""

    def __init__(
        self,
        model: str,
        api_key: str,
        timeout_seconds: float,
        api_base: str | None = None,
        supports_native_pdf: bool = True,
    ) -> None:
        self._model = model
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._api_base = api_base
        # Only Gemini's native API accepts a PDF as a `file` content block through LiteLLM — every
        # OpenAI-compatible gateway tried (OpenCode Go, every OpenRouter free vision model) rejects
        # it outright. Providers other than Gemini get pages rendered to PNG instead.
        self._supports_native_pdf = supports_native_pdf

    @property
    def model_name(self) -> str:
        return self._model

    def complete(self, prompt: str) -> str:
        return self._call([{"role": "user", "content": prompt}])

    def complete_vision(self, prompt: VisionPrompt) -> str:
        if prompt.content_type == "application/pdf" and not self._supports_native_pdf:
            return self._complete_vision_via_rendered_pages(prompt)

        encoded = base64.b64encode(prompt.content).decode("ascii")
        data_url = f"data:{prompt.content_type};base64,{encoded}"

        content: list[dict]
        if prompt.content_type == "application/pdf":
            content = [
                {"type": "text", "text": prompt.instruction},
                {"type": "file", "file": {"file_data": data_url}},
            ]
        else:
            content = [
                {"type": "text", "text": prompt.instruction},
                {"type": "image_url", "image_url": {"url": data_url}},
            ]

        return self._call([{"role": "user", "content": content}])

    def _complete_vision_via_rendered_pages(self, prompt: VisionPrompt) -> str:
        pages = render_pdf_pages_to_png(prompt.content)
        content: list[dict] = [{"type": "text", "text": prompt.instruction}]
        for page_png in pages:
            encoded = base64.b64encode(page_png).decode("ascii")
            content.append(
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{encoded}"}}
            )
        return self._call([{"role": "user", "content": content}])

    def _call(self, messages: list[dict]) -> str:
        try:
            response = litellm.completion(
                model=self._model,
                messages=messages,
                api_key=self._api_key,
                timeout=self._timeout_seconds,
                api_base=self._api_base,
            )
        except _RETRYABLE_EXCEPTIONS as error:
            raise RetryableLlmError(str(error)) from error
        except openai.APIStatusError as error:
            # A 5xx is transient; a 4xx (bad request, auth, not-found model) will fail identically
            # on retry. Every litellm exception with an HTTP status (BadRequestError,
            # AuthenticationError, NotFoundError, ...) is-a openai.APIStatusError — the sibling
            # litellm.exceptions.APIError class does NOT share a base with them, so catching that
            # instead would silently miss every 4xx here.
            if error.status_code >= 500:
                raise RetryableLlmError(str(error)) from error
            raise NonRetryableLlmError(str(error)) from error
        except openai.APIError as error:
            # Connection-level failures with no HTTP status (already-handled timeouts aside) —
            # never a provider-specific exception escaping the port, and not assumed retryable
            # without evidence it's transient.
            raise NonRetryableLlmError(str(error)) from error

        text = response.choices[0].message.content
        if not text:
            raise NonRetryableLlmError("Model returned an empty response")
        return text
