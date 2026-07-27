"""Extracts plain text from a policy PDF, page by page, in reading order."""

from __future__ import annotations

import pypdfium2 as pdfium


class UnreadablePdfError(RuntimeError):
    """The bytes could not be parsed as a PDF at all."""


def extract_pdf_text(pdf_bytes: bytes) -> str:
    """Returns the concatenated text of every page, separated by blank lines.

    :raises UnreadablePdfError: if the bytes are not a structurally valid PDF.
    """
    try:
        pdf = pdfium.PdfDocument(pdf_bytes)
    except pdfium.PdfiumError as error:
        raise UnreadablePdfError(f"Could not parse document as PDF: {error}") from error

    try:
        pages_text = []
        for page in pdf:
            textpage = page.get_textpage()
            try:
                pages_text.append(textpage.get_text_range())
            finally:
                textpage.close()
        return "\n\n".join(pages_text)
    finally:
        pdf.close()
