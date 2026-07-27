"""Turning a policy document's bytes into a schema-valid ``EmbedPolicyResponse``."""

from __future__ import annotations

import logging

from jsonschema import Draft202012Validator

from app.contracts import EMBED_POLICY_RESPONSE_SCHEMA, load_schema
from app.embeddings.client import EmbeddingClient
from app.policy.chunking import EmptyDocumentError, chunk_text
from app.policy.text_extraction import UnreadablePdfError, extract_pdf_text

logger = logging.getLogger(__name__)


class PolicyEmbeddingFailedError(RuntimeError):
    """The document could not be chunked and embedded into a schema-valid response."""


class PolicyEmbeddingService:
    """Coordinates text extraction, chunking and the embedding call."""

    def __init__(self, embedding_client: EmbeddingClient) -> None:
        self._embedding_client = embedding_client
        self._validator = Draft202012Validator(load_schema(EMBED_POLICY_RESPONSE_SCHEMA))

    def embed_policy(self, policy_document_id: str, content: bytes) -> dict:
        """Produce a schema-valid response for ``content``.

        :raises PolicyEmbeddingFailedError: if the document has no extractable text, the embedding
            call fails, or the resulting response does not satisfy the shared schema.
        """
        try:
            text = extract_pdf_text(content)
            chunks = chunk_text(text)
        except (EmptyDocumentError, UnreadablePdfError) as error:
            raise PolicyEmbeddingFailedError(str(error)) from error

        vectors = self._embedding_client.embed(chunks)

        response = {
            "policy_document_id": policy_document_id,
            "model": self._embedding_client.model_name,
            "embedding_dimensions": self._embedding_client.dimensions,
            "chunks": [
                {"chunk_index": index, "chunk_text": chunk, "embedding": vector}
                for index, (chunk, vector) in enumerate(zip(chunks, vectors))
            ],
        }

        errors = sorted(self._validator.iter_errors(response), key=lambda e: e.path)
        if errors:
            logger.warning(
                "Policy embedding produced a schema-invalid response: %s",
                "; ".join(error.message for error in errors[:5]),
            )
            raise PolicyEmbeddingFailedError("Embedding response did not satisfy the schema")

        return response
