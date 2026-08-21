from __future__ import annotations

import io

from pypdf import PdfWriter

from app.embedded_invoice import EmbeddedInvoiceFields, extract_embedded_invoice_fields


def pdf_with_attachment(name: str, content: bytes) -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(width=72, height=72)
    writer.add_attachment(name, content)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def ubl_invoice(*, total: str = "12.10", tax: str = "2.10", supplier: str | None = None) -> bytes:
    supplier_xml = (
        f"<AccountingSupplierParty><Party><PartyName><Name>{supplier}</Name></PartyName></Party></AccountingSupplierParty>"
        if supplier is not None
        else ""
    )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
  <ID>INV-42</ID>
  <IssueDate>2026-08-21</IssueDate>
  {supplier_xml}
  <TaxTotal><TaxAmount>{tax}</TaxAmount></TaxTotal>
  <LegalMonetaryTotal><PayableAmount currencyID="TRY">{total}</PayableAmount></LegalMonetaryTotal>
</Invoice>'''.encode()


def test_extracts_a_complete_ubl_header_from_an_embedded_invoice():
    fields = extract_embedded_invoice_fields(
        pdf_with_attachment("invoice.xml", ubl_invoice()), "application/pdf"
    )

    assert fields == EmbeddedInvoiceFields(
        invoice_number="INV-42",
        currency="TRY",
        total_minor=1210,
        tax_minor=210,
        document_date="2026-08-21",
    )


def test_extracts_the_supplier_party_name_without_considering_the_buyer():
    fields = extract_embedded_invoice_fields(
        pdf_with_attachment("invoice.xml", ubl_invoice(supplier="Issuer Company")), "application/pdf"
    )

    assert fields is not None
    assert fields.vendor == "Issuer Company"


def test_ignores_ambiguous_supplier_names():
    ambiguous = ubl_invoice(supplier="Issuer One").replace(
        b"</PartyName>", b"</PartyName><PartyName><Name>Issuer Two</Name></PartyName>"
    )

    fields = extract_embedded_invoice_fields(pdf_with_attachment("invoice.xml", ambiguous), "application/pdf")

    assert fields is not None
    assert fields.vendor is None

def test_ignores_non_ubl_or_incomplete_attachments():
    assert extract_embedded_invoice_fields(
        pdf_with_attachment("metadata.xml", b"<metadata />"), "application/pdf"
    ) is None
    assert extract_embedded_invoice_fields(
        pdf_with_attachment("invoice.xml", ubl_invoice(tax="")), "application/pdf"
    ) is None


def test_ignores_an_attachment_with_a_dtd_before_xml_parsing():
    xml = b'<!DOCTYPE Invoice [<!ENTITY repeated "123">]>' + ubl_invoice()

    assert extract_embedded_invoice_fields(pdf_with_attachment("invoice.xml", xml), "application/pdf") is None


def test_ignores_multiple_ubl_attachments_instead_of_choosing_one():
    writer = PdfWriter()
    writer.add_blank_page(width=72, height=72)
    writer.add_attachment("first.xml", ubl_invoice())
    writer.add_attachment("second.xml", ubl_invoice())
    output = io.BytesIO()
    writer.write(output)

    assert extract_embedded_invoice_fields(output.getvalue(), "application/pdf") is None
