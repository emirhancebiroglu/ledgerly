"""Extracts trustworthy header facts from UBL invoice XML embedded in a PDF."""

from __future__ import annotations

import io
import logging
import xml.etree.ElementTree as element_tree
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation

from pypdf import PdfReader

logger = logging.getLogger(__name__)

_UBL_INVOICE_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
_UBL_CREDIT_NOTE_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
_MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024


@dataclass(frozen=True)
class EmbeddedInvoiceFields:
    invoice_number: str
    currency: str
    total_minor: int
    tax_minor: int
    document_date: str
    vendor: str | None = None


def extract_embedded_invoice_fields(content: bytes, content_type: str) -> EmbeddedInvoiceFields | None:
    """Returns a complete UBL header only when one unambiguous attachment is present."""
    if content_type != "application/pdf":
        return None

    try:
        attachments = PdfReader(io.BytesIO(content)).attachments
    except Exception:
        logger.info("Embedded-invoice inspection could not read the PDF")
        return None

    fields = []
    for blobs in attachments.values():
        for blob in blobs:
            if len(blob) > _MAX_ATTACHMENT_BYTES:
                continue
            parsed = _parse_ubl_invoice(blob)
            if parsed is not None:
                fields.append(parsed)

    return fields[0] if len(fields) == 1 else None


def _parse_ubl_invoice(content: bytes) -> EmbeddedInvoiceFields | None:
    # UBL does not need a DTD. Reject declarations before parsing so a customer upload cannot
    # expand internal entities while this bounded, best-effort enrichment is being evaluated.
    uppercase_content = content.upper()
    if b"<!DOCTYPE" in uppercase_content or b"<!ENTITY" in uppercase_content:
        return None
    try:
        root = element_tree.fromstring(content)
    except element_tree.ParseError:
        return None

    multiplier = _document_multiplier(root.tag)
    invoice_number = _direct_text(root, "ID")
    issue_date = _direct_text(root, "IssueDate")
    payable_amount = _nested_element(root, "LegalMonetaryTotal", "PayableAmount")
    if multiplier is None or invoice_number is None or issue_date is None or payable_amount is None:
        return None
    if payable_amount.text is None:
        return None

    try:
        document_date = date.fromisoformat(issue_date).isoformat()
        total_minor = _to_minor(payable_amount.text) * multiplier
        currency = payable_amount.attrib["currencyID"]
        tax_minor = _tax_minor(root, multiplier)
    except (KeyError, ValueError, InvalidOperation):
        return None
    if tax_minor is None or len(currency) != 3 or not currency.isalpha():
        return None

    return EmbeddedInvoiceFields(
        invoice_number=invoice_number,
        currency=currency.upper(),
        total_minor=total_minor,
        tax_minor=tax_minor,
        document_date=document_date,
        vendor=_supplier_name(root),
    )


def _document_multiplier(tag: str) -> int | None:
    if tag == f"{{{_UBL_INVOICE_NAMESPACE}}}Invoice":
        return 1
    if tag == f"{{{_UBL_CREDIT_NOTE_NAMESPACE}}}CreditNote":
        return -1
    return None


def _direct_text(root: element_tree.Element, name: str) -> str | None:
    for child in root:
        if _local_name(child.tag) == name and child.text and child.text.strip():
            return child.text.strip()
    return None


def _nested_element(root: element_tree.Element, parent_name: str, child_name: str) -> element_tree.Element | None:
    for parent in root:
        if _local_name(parent.tag) != parent_name:
            continue
        for child in parent:
            if _local_name(child.tag) == child_name:
                return child
    return None


def _tax_minor(root: element_tree.Element, multiplier: int) -> int | None:
    tax_amounts = []
    for tax_total in root:
        if _local_name(tax_total.tag) != "TaxTotal":
            continue
        amount = next(
            (child.text for child in tax_total if _local_name(child.tag) == "TaxAmount" and child.text),
            None,
        )
        if amount is None:
            return None
        tax_amounts.append(_to_minor(amount))
    return sum(tax_amounts) * multiplier if tax_amounts else None


def _supplier_name(root: element_tree.Element) -> str | None:
    """Read the UBL supplier role only; buyer and delivery-party names are never candidates."""
    supplier_party = next(
        (child for child in root if _local_name(child.tag) == "AccountingSupplierParty"), None
    )
    if supplier_party is None:
        return None
    party = next((child for child in supplier_party if _local_name(child.tag) == "Party"), None)
    if party is None:
        return None
    for parent_name, child_name in (
        ("PartyName", "Name"),
        ("PartyLegalEntity", "RegistrationName"),
    ):
        names = {
            child.text.strip()
            for parent in party
            if _local_name(parent.tag) == parent_name
            for child in parent
            if _local_name(child.tag) == child_name and child.text and child.text.strip()
        }
        if len(names) == 1:
            return names.pop()
        if len(names) > 1:
            return None
    return None


def _to_minor(value: str) -> int:
    minor_value = Decimal(value.strip()) * 100
    if minor_value != minor_value.to_integral_value():
        raise ValueError("Amount has more than two decimal places")
    return int(minor_value)


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]
