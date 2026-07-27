"""A deterministic stand-in for a real embedding model.

Exercised by the real ``POST /embed-policy`` route, same as ``app.llm.fake.FakeLlmClient`` is for
extraction — output is derived from the input text so identical chunks embed identically, keeping
tests non-flaky without a network call.
"""

from __future__ import annotations

import hashlib

from app.embeddings.client import EmbeddingClient

DIMENSIONS = 8


class FakeEmbeddingClient(EmbeddingClient):
    """Hash-derived pseudo-embedding, normalized to unit length like a real model's output."""

    MODEL_NAME = "fake-embedding-v1"

    @property
    def model_name(self) -> str:
        return self.MODEL_NAME

    @property
    def dimensions(self) -> int:
        return DIMENSIONS

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        raw = [digest[i] / 255.0 for i in range(DIMENSIONS)]
        norm = sum(v * v for v in raw) ** 0.5
        if norm == 0:
            return raw
        return [v / norm for v in raw]
