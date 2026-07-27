"""The real ``EmbeddingClient`` adapter, backed by LiteLLM's embedding interface."""

from __future__ import annotations

import litellm
import openai
from litellm.exceptions import APIConnectionError, RateLimitError, ServiceUnavailableError, Timeout

from app.embeddings.client import EmbeddingClient, EmbeddingError

_RETRYABLE_EXCEPTIONS = (Timeout, RateLimitError, ServiceUnavailableError, APIConnectionError)


class LiteLlmEmbeddingClient(EmbeddingClient):
    """Calls a real embedding model through LiteLLM's embeddings interface."""

    def __init__(
        self,
        model: str,
        api_key: str,
        dimensions: int,
        timeout_seconds: float,
        api_base: str | None = None,
    ) -> None:
        self._model = model
        self._api_key = api_key
        self._dimensions = dimensions
        self._timeout_seconds = timeout_seconds
        self._api_base = api_base

    @property
    def model_name(self) -> str:
        return self._model

    @property
    def dimensions(self) -> int:
        return self._dimensions

    def embed(self, texts: list[str]) -> list[list[float]]:
        try:
            response = litellm.embedding(
                model=self._model,
                input=texts,
                api_key=self._api_key,
                timeout=self._timeout_seconds,
                api_base=self._api_base,
            )
        except _RETRYABLE_EXCEPTIONS as error:
            raise EmbeddingError(str(error)) from error
        except openai.APIError as error:
            raise EmbeddingError(str(error)) from error

        return [item["embedding"] for item in response.data]
