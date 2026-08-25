from __future__ import annotations

import json
from pathlib import Path

import pypdf
import pytest

DEMO_SEED_DIR = Path(__file__).parent.parent / "demo_seed"
INVOICE_DIR = DEMO_SEED_DIR / "pdfs" / "invoices"
MANIFEST_PATH = DEMO_SEED_DIR / "invoice_manifest.json"


def load_manifest() -> list[dict]:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))["entries"]


def test_manifest_exists_and_is_valid_json() -> None:
    assert MANIFEST_PATH.is_file()
    entries = load_manifest()
    assert len(entries) >= 18, "corpus should cover a believable ~3-month history (18-24 invoices)"
    assert len(entries) <= 24


@pytest.mark.parametrize("entry", load_manifest(), ids=lambda e: e["path"])
def test_every_manifest_entry_has_an_extractable_pdf(entry: dict) -> None:
    path = DEMO_SEED_DIR / entry["path"]
    assert path.is_file(), f"{entry['path']} listed in manifest but missing on disk"

    reader = pypdf.PdfReader(str(path))
    text = "".join(page.extract_text() for page in reader.pages)
    assert len(text) > 20, f"{entry['path']} has no meaningful extractable text layer"


def test_every_scenario_type_the_seed_needs_is_represented() -> None:
    scenarios = {e["scenario"] for e in load_manifest()}
    required = {"normal", "low_confidence", "budget_threshold", "duplicate_confirmed", "anomaly_high"}
    missing = required - scenarios
    assert not missing, f"manifest is missing required scenario(s): {missing}"


def test_duplicate_confirmed_entry_shares_vendor_and_invoice_number_with_an_earlier_entry() -> None:
    entries = load_manifest()
    duplicate = next(e for e in entries if e["scenario"] == "duplicate_confirmed")
    earlier = [
        e
        for e in entries
        if e["scenario"] != "duplicate_confirmed"
        and e["expected"]["vendor"] == duplicate["expected"]["vendor"]
        and e["expected"]["invoice_number"] == duplicate["expected"]["invoice_number"]
    ]
    assert earlier, "duplicate_confirmed entry must match an earlier entry's vendor + invoice_number"


def test_budget_threshold_entries_share_a_category_and_month() -> None:
    entries = [e for e in load_manifest() if e["scenario"] == "budget_threshold"]
    assert len(entries) >= 3, "need enough same-category, same-month spend to cross a threshold"
    categories = {e["category"] for e in entries}
    assert len(categories) == 1
    months = {e["expected"]["document_date"][:7] for e in entries}
    assert len(months) == 1


def test_anomaly_entry_is_a_clear_outlier_against_its_own_vendors_other_amounts() -> None:
    entries = load_manifest()
    outlier = next(e for e in entries if e["scenario"] == "anomaly_high")
    same_vendor_normal = [
        e["expected"]["total_minor"]
        for e in entries
        if e["expected"]["vendor"] == outlier["expected"]["vendor"] and e["scenario"] == "normal"
    ]
    assert same_vendor_normal, "anomaly entry's vendor needs normal-scenario history to be anomalous against"
    average = sum(same_vendor_normal) / len(same_vendor_normal)
    assert outlier["expected"]["total_minor"] > average * 20, "outlier should be dramatically larger, not just above average"
