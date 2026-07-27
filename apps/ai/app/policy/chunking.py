"""Splits policy document text into overlapping chunks for embedding.

Overlap keeps a sentence that straddles a chunk boundary readable from at least one chunk, which
matters for retrieval: a policy rule cut exactly in half would be invisible to a similarity search
for either half's chunk.
"""

from __future__ import annotations

CHUNK_SIZE_CHARS = 1500
CHUNK_OVERLAP_CHARS = 200


class EmptyDocumentError(RuntimeError):
    """The document contained no extractable text."""


def chunk_text(text: str) -> list[str]:
    """Splits ``text`` into overlapping chunks, in order.

    :raises EmptyDocumentError: if ``text`` has no non-whitespace content.
    """
    stripped = text.strip()
    if not stripped:
        raise EmptyDocumentError("Document contains no extractable text")

    if len(stripped) <= CHUNK_SIZE_CHARS:
        return [stripped]

    chunks: list[str] = []
    start = 0
    step = CHUNK_SIZE_CHARS - CHUNK_OVERLAP_CHARS
    while start < len(stripped):
        end = min(start + CHUNK_SIZE_CHARS, len(stripped))
        chunk = stripped[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end == len(stripped):
            break
        start += step
    return chunks
