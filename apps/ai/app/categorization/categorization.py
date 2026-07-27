"""Turning extracted fields, an org's taxonomy and retrieved policy chunks into a category."""

from __future__ import annotations

import logging

from jsonschema import Draft202012Validator

from app.categorization.categorization_graph import (
    CategorizationFailedError as GraphCategorizationFailedError,
)
from app.categorization.categorization_graph import run_categorization_graph
from app.categorization.prompt_markers import CATEGORIES_MARKER, POLICY_CHUNKS_MARKER
from app.contracts import CATEGORIZE_RESPONSE_SCHEMA, load_schema
from app.llm.client import LlmClient

logger = logging.getLogger(__name__)

CATEGORIZATION_INSTRUCTION_TEMPLATE = """\
Classify this expense into exactly one of the organization's categories, using the policy \
excerpts below as evidence where relevant. Return ONLY a single JSON object — no markdown code \
fences, no commentary before or after — matching exactly this shape:

{{
  "category": "<one of the allowed categories, verbatim>",
  "confidence": <float 0-1>,
  "citation": "<the exact policy chunk text that justified this category, verbatim, or null if \
no policy chunk was relevant>"
}}

""" + CATEGORIES_MARKER + """ {categories}

Expense:
  vendor: {vendor}
  currency: {currency}
  total_minor: {total_minor}
  document_date: {document_date}

""" + POLICY_CHUNKS_MARKER + """
{policy_chunks}

Never invent a category outside the list above. Never invent a citation that is not one of the \
policy excerpts verbatim — if none apply, use null.\
"""


class CategorizationFailedError(RuntimeError):
    """The document could not be categorized into a schema-valid response."""


class CategorizationService:
    """Coordinates the model call and enforces the outgoing contract."""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client
        self._validator = Draft202012Validator(load_schema(CATEGORIZE_RESPONSE_SCHEMA))

    def categorize(
        self,
        document_id: str,
        vendor: str | None,
        currency: str,
        total_minor: int,
        document_date: str | None,
        categories: list[str],
        policy_chunks: list[str],
    ) -> dict:
        """Produce a category classification for the given expense.

        :raises CategorizationFailedError: if the model fails, its output is not schema-valid
            JSON, or it chose a category outside the given list.
        """
        instruction = CATEGORIZATION_INSTRUCTION_TEMPLATE.format(
            categories=", ".join(categories),
            vendor=vendor or "unknown",
            currency=currency,
            total_minor=total_minor,
            document_date=document_date or "unknown",
            policy_chunks="\n".join(f"- {chunk}" for chunk in policy_chunks)
            if policy_chunks
            else "(no policy documents matched this expense)",
        )

        try:
            classified = run_categorization_graph(self._llm_client, instruction)
        except GraphCategorizationFailedError as error:
            raise CategorizationFailedError(str(error)) from error

        response = {
            **classified,
            "document_id": document_id,
            "model": self._llm_client.model_name,
        }

        if response.get("category") not in categories:
            logger.warning(
                "Model chose a category outside the allowed taxonomy for document %s", document_id
            )
            raise CategorizationFailedError("Model chose a category outside the given taxonomy")

        errors = sorted(self._validator.iter_errors(response), key=lambda e: e.path)
        if errors:
            logger.warning(
                "Categorization produced a schema-invalid response: %s",
                "; ".join(error.message for error in errors[:5]),
            )
            raise CategorizationFailedError("Categorization did not satisfy the response schema")

        return response
