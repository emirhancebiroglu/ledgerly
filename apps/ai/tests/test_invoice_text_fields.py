from app.invoice_text_fields import extract_labelled_invoice_number


def test_extracts_an_explicitly_labelled_invoice_number(monkeypatch):
    monkeypatch.setattr(
        "app.invoice_text_fields.extract_pdf_text",
        lambda _content: "Fatura No: INV-42/2026\nOther facts",
    )

    assert extract_labelled_invoice_number(b"pdf", "application/pdf") == "INV-42/2026"


def test_does_not_treat_unlabelled_prose_as_an_invoice_number(monkeypatch):
    monkeypatch.setattr(
        "app.invoice_text_fields.extract_pdf_text",
        lambda _content: "The invoice number must be supplied separately.",
    )

    assert extract_labelled_invoice_number(b"pdf", "application/pdf") is None


def test_extracts_a_wrapped_alphanumeric_invoice_identifier(monkeypatch):
    monkeypatch.setattr(
        "app.invoice_text_fields.extract_pdf_text",
        lambda _content: "Fatura\nSeri / Sıra\nNo\nAB1C234567890123",
    )

    assert extract_labelled_invoice_number(b"pdf", "application/pdf") == "AB1C234567890123"


def test_does_not_accept_a_wrapped_numeric_reference_as_an_invoice_identifier(monkeypatch):
    monkeypatch.setattr(
        "app.invoice_text_fields.extract_pdf_text",
        lambda _content: "Fatura\nReference No\n1234567890",
    )

    assert extract_labelled_invoice_number(b"pdf", "application/pdf") is None
