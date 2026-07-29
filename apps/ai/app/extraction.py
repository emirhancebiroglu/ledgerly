"""Turning document bytes into a schema-valid ``ExtractionProposal``.

The service owns one job: call the configured ``LlmClient``, shape its answer into the contract,
and refuse to emit anything that does not satisfy the shared schema. It deliberately does *not*
decide whether a proposal is trustworthy — the arithmetic, currency allow-list, date range and
amount ceiling are `api`'s call, at the trust boundary described in ``docs/architecture.md``.
"""

from __future__ import annotations

import logging

from jsonschema import Draft202012Validator

from app.contracts import EXTRACTION_PROPOSAL_SCHEMA, load_schema
from app.llm.client import LlmClient
from app.llm.extraction_graph import ExtractionFailedError as GraphExtractionFailedError
from app.llm.extraction_graph import run_extraction_graph

logger = logging.getLogger(__name__)

EXTRACTION_INSTRUCTION = """\
Read this document and return ONLY a single JSON object — no markdown code fences, no \
commentary before or after — matching exactly this shape:

{
  "vendor": "<name as printed, or null if unreadable>",
  "invoice_number": "<invoice or receipt number as printed, or null if unreadable or absent>",
  "currency": "<ISO 4217 alphabetic code, e.g. TRY, USD, EUR, GBP, MYR — never a symbol like $ or a \
local abbreviation like TL>",
  "total_minor": <integer, the final payable amount in minor units (cents/kuruş), e.g. 12.10 -> \
1210. Never a float.>,
  "tax_minor": <integer, the tax portion already included in total_minor, in minor units. Never a \
float. 0 if the document is genuinely tax-exempt, never omitted.>,
  "document_date": "<YYYY-MM-DD — the date the document was ISSUED or CUT, e.g. \\"Fatura Tarihi\\" \
or \\"Invoice Date\\". NEVER the due date, order date, dispatch date or upload date, even if a \
label like \\"Sipariş Tarihi\\" or \\"Son Ödeme Tarihi\\" is more prominent on the page.>",
  "lines": [
    {"description": "<line text>", "quantity": <integer, thousandths — so 1.5 units is 1500>, \
"amount_minor": <integer, pre-tax/net minor units>}
  ],
  "confidence": {
    "vendor": <float 0-1>,
    "currency": <float 0-1>,
    "total_minor": <float 0-1>,
    "tax_minor": <float 0-1>,
    "document_date": <float 0-1>
  }
}

"lines" may be an empty array if the document has no itemisation. Every line amount_minor is the \
pre-tax/net line total: DO NOT include tax in it. When lines are present, their sum plus tax_minor \
MUST equal total_minor exactly. If you cannot derive reliable pre-tax line values, return an empty \
lines array rather than guessing. Every amount is an integer in minor units — never a float \
anywhere. A refund or credit note has a negative total_minor and tax_minor, with every line \
amount_minor also negative.\
"""


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
            result = run_extraction_graph(
                self._llm_client, EXTRACTION_INSTRUCTION, content, content_type
            )
        except GraphExtractionFailedError as error:
            raise ExtractionFailedError(str(error)) from error

        extracted = result["extracted"]
        if result["self_checked_fields"]:
            logger.info(
                "Self-check ran for document %s on fields: %s",
                document_id,
                ", ".join(result["self_checked_fields"]),
            )

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
            logger.warning("Extraction produced a schema-invalid proposal")
            raise ExtractionFailedError("Extraction did not satisfy the proposal schema")

        return proposal
