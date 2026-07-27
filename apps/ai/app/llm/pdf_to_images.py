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
