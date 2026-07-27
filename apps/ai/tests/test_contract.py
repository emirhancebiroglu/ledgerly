"""The `ai` side of the shared contract.

This file and the `api` side's ``ExtractionContractTest`` load the very same schemas and the very
same golden examples from ``docs/contracts/``. Neither side restates the field list, so the two
cannot drift apart without one of these suites failing.
"""

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from app.contracts import (
    EXTRACT_REQUEST_SCHEMA,
    EXTRACTION_PROPOSAL_SCHEMA,
    contracts_directory,
    load_example,
    load_schema,
)


def test_contracts_directory_resolves_to_the_shared_location():
    directory = contracts_directory()
    assert directory.is_dir()
    assert (directory / EXTRACTION_PROPOSAL_SCHEMA).is_file()
    assert (directory / EXTRACT_REQUEST_SCHEMA).is_file()


def test_both_schemas_are_valid_json_schema():
    for name in (EXTRACTION_PROPOSAL_SCHEMA, EXTRACT_REQUEST_SCHEMA):
        Draft202012Validator.check_schema(load_schema(name))


def test_the_golden_valid_proposal_validates_green():
    validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))
    validator.validate(load_example("extraction-proposal.valid.json"))


def test_the_golden_valid_request_validates_green():
    validator = Draft202012Validator(load_schema(EXTRACT_REQUEST_SCHEMA))
    validator.validate(load_example("extract-request.valid.json"))


def test_a_proposal_missing_total_minor_validates_red():
    validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))
    errors = list(validator.iter_errors(load_example("extraction-proposal.missing-total.json")))
    assert errors, "total_minor is required"
    assert any("total_minor" in error.message for error in errors)


def test_a_proposal_with_a_float_amount_validates_red():
    validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))
    errors = list(validator.iter_errors(load_example("extraction-proposal.float-amount.json")))
    assert errors, "money must be an integer of minor units, never a float"


def test_every_monetary_field_is_declared_integer():
    schema = load_schema(EXTRACTION_PROPOSAL_SCHEMA)
    assert schema["properties"]["total_minor"]["type"] == "integer"
    assert schema["properties"]["tax_minor"]["type"] == "integer"
    assert schema["$defs"]["line"]["properties"]["amount_minor"]["type"] == "integer"


def test_per_field_confidence_is_required():
    schema = load_schema(EXTRACTION_PROPOSAL_SCHEMA)
    required = schema["properties"]["confidence"]["required"]
    assert {"currency", "total_minor", "tax_minor", "document_date"} <= set(required)


def test_an_unknown_top_level_field_is_rejected():
    validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))
    proposal = load_example("extraction-proposal.valid.json") | {"smuggled_field": "surprise"}

    errors = list(validator.iter_errors(proposal))

    assert errors, "additionalProperties is false, so an unexpected field is a contract break"


def test_an_unknown_currency_shape_is_rejected_by_the_schema():
    validator = Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA))
    proposal = load_example("extraction-proposal.valid.json") | {"currency": "euro"}

    try:
        validator.validate(proposal)
    except ValidationError:
        return
    raise AssertionError("currency must match the ISO 4217 three-letter pattern")
