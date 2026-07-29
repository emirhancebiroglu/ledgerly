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
lines array rather than guessing. Before returning lines, calculate that equality; if it fails \
because the document has tax-inclusive amounts or multiple document-level taxes, return an empty \
lines array. Every amount is an integer in minor units — never a float \
anywhere. A refund or credit note has a negative total_minor and tax_minor, with every line \
amount_minor also negative.\
"""

_UNRECONCILED_LINE_WARNING = (
    "Line items were omitted because they did not reconcile with the invoice-level totals."
)


def _is_integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _normalize_unreconciled_lines(extracted: dict) -> dict:
    """Drop advisory line items that cannot safely support the invoice totals.

    The ledger posts the verified document total, not individual source lines. Some invoices expose
    tax-inclusive service amounts alongside a document-level tax breakdown, so asking the model to
    allocate pre-tax values can produce internally inconsistent lines despite correct header data.
    An empty list is an explicit schema-supported representation for that case; fabricating a
    balancing allocation would be less trustworthy than preserving the invoice-level facts.
    """
    lines = extracted.get("lines")
    if not isinstance(lines, list) or not lines:
        return extracted

    total_minor = extracted.get("total_minor")
    tax_minor = extracted.get("tax_minor")
    if not _is_integer(total_minor) or not _is_integer(tax_minor):
        return extracted

    line_amounts: list[int] = []
    for line in lines:
        if not isinstance(line, dict) or not _is_integer(line.get("amount_minor")):
            return extracted
        line_amounts.append(line["amount_minor"])

    is_refund = total_minor < 0
    signs_match = all(amount <= 0 if is_refund else amount >= 0 for amount in line_amounts)
    reconciles = sum(line_amounts) + tax_minor == total_minor
    if signs_match and reconciles:
        return extracted

    warnings = extracted.get("warnings")
    if warnings is not None and (
        not isinstance(warnings, list) or not all(isinstance(warning, str) for warning in warnings)
    ):
        return extracted

    normalized = {**extracted, "lines": []}
    normalized["warnings"] = [*(warnings or []), _UNRECONCILED_LINE_WARNING]
    logger.info("Omitted unreconciled advisory line items from extraction proposal")
    return normalized


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

        extracted = _normalize_unreconciled_lines(result["extracted"])
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
