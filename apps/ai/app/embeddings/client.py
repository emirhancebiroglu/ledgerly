"""The ``EmbeddingClient`` port.

Mirrors ``app.llm.client.LlmClient``: a narrow interface so the embedding provider is a
configuration choice, not a code change, and so policy-chunking logic can be tested against a
deterministic stub without a network call.
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class EmbeddingError(RuntimeError):
    """The embedding model could not produce a usable vector."""


class EmbeddingClient(ABC):
    """Port for turning text into embedding vectors."""

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Identifier recorded on every embedded chunk, so a vector is traceable to what produced it."""

    @property
    @abstractmethod
    def dimensions(self) -> int:
        """Length of every vector this client returns."""

    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        """Embeds a batch of texts, returning one vector per input in the same order.

        :raises EmbeddingError: if the model cannot produce a response.
        """
