"""Turning document bytes into a schema-valid ``ExtractionProposal``.

The service owns one job: call the configured ``LlmClient``, shape its answer into the contract,
and refuse to emit anything that does not satisfy the shared schema. It deliberately does *not*
decide whether a proposal is trustworthy — the arithmetic, currency allow-list, date range and
amount ceiling are `api`'s call, at the trust boundary described in ``docs/architecture.md``.
"""

from __future__ import annotations

import json
import logging

from jsonschema import Draft202012Validator

from app.contracts import EXTRACTION_PROPOSAL_SCHEMA, load_schema
from app.llm.client import LlmClient, LlmError, VisionPrompt

logger = logging.getLogger(__name__)

EXTRACTION_INSTRUCTION = (
    "Extract the vendor, currency, document date, tax and total from this document. "
    "Report every monetary amount in minor units as an integer. "
    "Report a confidence in [0,1] for each field."
)


class ExtractionFailedError(RuntimeError):
    """The document could not be extracted into a schema-valid proposal."""


class ExtractionService:
    """Coordinates the model call and enforces the outgoing contract."""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client
        self._validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))

    def extract(self, document_id: str, content: bytes, content_type: str) -> dict:
        """Produce a proposal for ``content``.

        :raises ExtractionFailedError: if the model fails or its output does not satisfy the
            shared schema. Emitting a malformed proposal would push the problem onto `api` as a
            parse error instead of surfacing it here, where the cause is known.
        """
        try:
            raw = self._llm_client.complete_vision(
                VisionPrompt(
                    instruction=EXTRACTION_INSTRUCTION,
                    content=content,
                    content_type=content_type,
                )
            )
        except LlmError as error:
            raise ExtractionFailedError(str(error)) from error

        try:
            extracted = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ExtractionFailedError("Model returned output that is not valid JSON") from error

        proposal = {
            **extracted,
            # Set here, not by the model: a proposal must carry the id of the document it was
            # actually derived from, and the model has no business choosing it.
            "document_id": document_id,
            "model": self._llm_client.model_name,
        }

        errors = sorted(self._validator.iter_errors(proposal), key=lambda e: e.path)
        if errors:
            # The proposal itself is not logged — a document's contents are the customer's.
            logger.warning(
                "Extraction produced a schema-invalid proposal: %s",
                "; ".join(error.message for error in errors[:5]),
            )
            raise ExtractionFailedError("Extraction did not satisfy the proposal schema")

        return proposal
