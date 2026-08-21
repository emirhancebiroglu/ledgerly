"""Renders PDF pages to PNG bytes.

Only Gemini's native API accepts a PDF as a ``file`` content block through LiteLLM — every other
provider tried (OpenCode Go's gateway, every OpenRouter free vision model) rejects it outright.
Converting to images first is provider-agnostic and works everywhere a vision model accepts
``image_url`` blocks, which is universal.
"""

from __future__ import annotations

import io

import pypdfium2 as pdfium

# Vision models read invoices fine at a moderate DPI; higher only costs tokens without improving
# accuracy on printed text.
RENDER_SCALE = 2.0
# Invoice issuers are conventionally placed in the page header. Keeping the crop deliberately
# generous retains letterheads and seller identifiers while excluding the usual bill-to section.
HEADER_CROP_FRACTION = 0.42


class PdfRenderError(RuntimeError):
    """A PDF could not be rendered into an advisory visual crop."""


def render_pdf_pages_to_png(pdf_bytes: bytes) -> list[bytes]:
    """Returns one PNG per page, in order."""
    pdf = pdfium.PdfDocument(pdf_bytes)
    try:
        pages = []
        for page in pdf:
            bitmap = page.render(scale=RENDER_SCALE)
            pil_image = bitmap.to_pil()
            buffer = io.BytesIO()
            pil_image.save(buffer, format="PNG")
            pages.append(buffer.getvalue())
        return pages
    finally:
        pdf.close()


def render_first_page_header_to_png(pdf_bytes: bytes) -> bytes:
    """Render the upper header of the first page as a PNG.

    This is a focused visual input for issuer identification, not a replacement for full-document
    rendering. Callers retain the full-page path for document-level facts.
    """
    try:
        pdf = pdfium.PdfDocument(pdf_bytes)
        try:
            page = pdf[0]
            try:
                bitmap = page.render(scale=RENDER_SCALE)
                image = bitmap.to_pil()
                header_height = max(1, round(image.height * HEADER_CROP_FRACTION))
                header = image.crop((0, 0, image.width, header_height))
                buffer = io.BytesIO()
                header.save(buffer, format="PNG")
                return buffer.getvalue()
            finally:
                page.close()
        finally:
            pdf.close()
    except (pdfium.PdfiumError, IndexError, OSError, ValueError) as error:
        raise PdfRenderError("Could not render the first-page header") from error
