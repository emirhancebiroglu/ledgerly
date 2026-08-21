"""Conservative, label-grounded facts available in a text PDF."""

from __future__ import annotations

import re

from app.policy.text_extraction import UnreadablePdfError, extract_pdf_text


# Require an explicit separator after an invoice-like label. This avoids treating prose such as
# "invoice number is required" as a document identifier. UBL remains more authoritative.
_LABELLED_INVOICE_NUMBER = re.compile(
    r"(?im)(?:invoice|fatura|belge)\s*(?:number|numara|no|#)?\s*[:#-]\s*"
    r"([A-Za-z0-9][A-Za-z0-9./_-]{2,})"
)
_WRAPPED_LABELLED_INVOICE_NUMBER = re.compile(
    r"(?im)(?:invoice|fatura|belge)[\s\S]{0,32}?\b(?:number|numara|no)\b\s*[:#-]?\s*"
    r"([A-Za-z0-9][A-Za-z0-9./_-]{2,})"
)


def extract_labelled_invoice_number(content: bytes, content_type: str) -> str | None:
    """Return the first explicitly labelled invoice identifier from a text PDF, if any."""
    if content_type != "application/pdf":
        return None
    try:
        text = extract_pdf_text(content)
    except UnreadablePdfError:
        return None
    match = _LABELLED_INVOICE_NUMBER.search(text)
    if match is not None:
        return match.group(1)
    wrapped_match = _WRAPPED_LABELLED_INVOICE_NUMBER.search(text)
    if wrapped_match is None:
        return None
    candidate = wrapped_match.group(1)
    return candidate if any(char.isalpha() for char in candidate) and any(char.isdigit() for char in candidate) else None
