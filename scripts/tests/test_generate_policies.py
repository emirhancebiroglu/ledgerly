from __future__ import annotations

from pathlib import Path

import pypdf
import pytest

POLICY_DIR = Path(__file__).parent.parent / "demo_seed" / "pdfs" / "policies"

EXPECTED_FILES = [
    "travel_and_expense_policy.pdf",
    "software_and_subscriptions_policy.pdf",
    "client_entertainment_policy.pdf",
]


@pytest.mark.parametrize("filename", EXPECTED_FILES)
def test_policy_pdf_exists_with_extractable_text(filename: str) -> None:
    path = POLICY_DIR / filename
    assert path.is_file(), f"{filename} was not generated — run generate_policies.py first"

    reader = pypdf.PdfReader(str(path))
    text = "".join(page.extract_text() for page in reader.pages)

    assert len(text) > 100, f"{filename} has no meaningful extractable text layer"


def test_exactly_the_expected_policy_files_are_present() -> None:
    actual = {p.name for p in POLICY_DIR.glob("*.pdf")}
    assert actual == set(EXPECTED_FILES)
