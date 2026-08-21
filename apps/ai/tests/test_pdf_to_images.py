"""PDF page rendering used by non-Gemini providers, which reject the PDF `file` content block."""

from __future__ import annotations

import io
from pathlib import Path

from PIL import Image

from app.llm.pdf_to_images import render_first_page_header_to_png, render_pdf_pages_to_png

FIXTURES_DIR = Path(__file__).resolve().parents[1] / "evals" / "fixtures" / "synthetic"


def test_renders_one_png_per_page():
    pdf_bytes = (FIXTURES_DIR / "02_no_itemisation.pdf").read_bytes()

    pages = render_pdf_pages_to_png(pdf_bytes)

    assert len(pages) == 1


def test_each_rendered_page_is_a_valid_png():
    pdf_bytes = (FIXTURES_DIR / "02_no_itemisation.pdf").read_bytes()

    pages = render_pdf_pages_to_png(pdf_bytes)

    assert pages[0].startswith(b"\x89PNG\r\n\x1a\n")


def test_rendered_pages_are_non_trivially_sized():
    """A blank/corrupt render would come back tiny; a real invoice page should not."""
    pdf_bytes = (FIXTURES_DIR / "03_zero_tax_exempt.pdf").read_bytes()

    pages = render_pdf_pages_to_png(pdf_bytes)

    assert len(pages[0]) > 1000


def test_renders_a_smaller_first_page_header_png():
    full_page = render_pdf_pages_to_png((FIXTURES_DIR / "02_no_itemisation.pdf").read_bytes())[0]
    header = render_first_page_header_to_png((FIXTURES_DIR / "02_no_itemisation.pdf").read_bytes())

    with Image.open(io.BytesIO(full_page)) as page_image, Image.open(io.BytesIO(header)) as header_image:
        assert header.startswith(b"\x89PNG\r\n\x1a\n")
        assert header_image.width == page_image.width
        assert 0 < header_image.height < page_image.height
