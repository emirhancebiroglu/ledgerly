"""T6 — fixture validation.

A typo in a hand-written fixture must fail loudly here, not silently score as a model miss during
the eval run. Also proves the group composition the M5 plan committed to: 20 documents across
three groups, at least one non-TRY currency, no float anywhere in an expected amount.
"""

from __future__ import annotations

import sys
from pathlib import Path

from jsonschema import Draft202012Validator

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.contracts import EXTRACTION_PROPOSAL_SCHEMA, load_schema
from evals.fixtures_loader import GROUPS, load_fixtures

FIXTURES = load_fixtures()
VALIDATOR = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))


def as_proposal(expected: dict) -> dict:
    """Fixtures omit document_id/model/confidence — fill them with placeholders that satisfy the
    schema, so this test validates the fixture's own fields, not the fields it deliberately
    doesn't specify."""
    return {
        **expected,
        "document_id": "00000000-0000-0000-0000-000000000000",
        "model": "fixture-validation-placeholder",
        "confidence": {
            field: 1.0
            for field in ("currency", "total_minor", "tax_minor", "document_date")
        },
    }


def test_there_are_exactly_20_fixtures_across_the_three_groups():
    assert len(FIXTURES) == 20


def test_there_are_11_real_fixtures():
    assert len([f for f in FIXTURES if f.group == "real"]) == 11


def test_there_are_6_public_dataset_fixtures():
    assert len([f for f in FIXTURES if f.group == "public"]) == 6


def test_there_are_3_synthetic_fixtures():
    assert len([f for f in FIXTURES if f.group == "synthetic"]) == 3


def test_every_group_is_represented():
    groups_present = {f.group for f in FIXTURES}
    assert groups_present == set(GROUPS)


def test_every_fixture_expected_output_satisfies_the_shared_schema():
    for fixture in FIXTURES:
        errors = sorted(VALIDATOR.iter_errors(as_proposal(fixture.expected)), key=lambda e: e.path)
        assert not errors, (
            f"{fixture.name} does not satisfy the proposal schema: "
            f"{'; '.join(e.message for e in errors)}"
        )


def test_every_currency_is_an_iso_4217_alphabetic_code():
    for fixture in FIXTURES:
        currency = fixture.expected["currency"]
        assert len(currency) == 3 and currency.isupper() and currency.isalpha(), (
            f"{fixture.name} has a non-ISO currency: {currency}"
        )


def test_at_least_one_fixture_is_not_try():
    non_try = [f for f in FIXTURES if f.expected["currency"] != "TRY"]
    assert len(non_try) > 0


def test_no_amount_field_is_a_float_anywhere_in_any_fixture():
    for fixture in FIXTURES:
        expected = fixture.expected
        for field in ("total_minor", "tax_minor"):
            value = expected[field]
            assert isinstance(value, int) and not isinstance(value, bool), (
                f"{fixture.name}.{field} is not an int: {value!r}"
            )
        for line in expected.get("lines", []):
            assert isinstance(line["amount_minor"], int), (
                f"{fixture.name} has a non-int line amount: {line['amount_minor']!r}"
            )


def test_every_document_date_matches_the_iso_pattern():
    import re

    pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
    for fixture in FIXTURES:
        date = fixture.expected["document_date"]
        assert pattern.match(date), f"{fixture.name} has a non-ISO document_date: {date!r}"


def test_real_fixture_sources_point_into_the_gitignored_invoices_directory():
    for fixture in FIXTURES:
        if fixture.group != "real":
            continue
        assert "invoices" in fixture.source_path.parts, (
            f"{fixture.name} does not point into invoices/: {fixture.source_path}"
        )


def test_public_and_synthetic_fixture_sources_are_committed_alongside_the_fixture():
    for fixture in FIXTURES:
        if fixture.group == "real":
            continue
        assert fixture.source_path.is_file(), (
            f"{fixture.name} source is missing: {fixture.source_path}"
        )
