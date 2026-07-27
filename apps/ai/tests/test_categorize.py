"""`POST /categorize` — the M6 categorization endpoint."""

import uuid

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.categorization.categorization import CategorizationFailedError, CategorizationService
from app.contracts import CATEGORIZE_RESPONSE_SCHEMA, load_schema
from app.llm.client import LlmClient, LlmError
from app.llm.fake import FakeLlmClient
from app.main import app

client = TestClient(app)


def post_categorize(**overrides):
    body = {
        "document_id": overrides.pop("document_id", str(uuid.uuid4())),
        "vendor": overrides.pop("vendor", "Contoso Travel"),
        "currency": overrides.pop("currency", "EUR"),
        "total_minor": overrides.pop("total_minor", 60000),
        "document_date": overrides.pop("document_date", "2026-07-14"),
        "categories": overrides.pop("categories", ["Travel", "Office Supplies", "Meals"]),
        "policy_chunks": overrides.pop(
            "policy_chunks",
            [{"chunk_text": "Travel expenses over 500 EUR require manager approval."}],
        ),
    }
    body.update(overrides)
    return client.post("/categorize", json=body)


def test_a_request_returns_200_with_a_schema_valid_body():
    response = post_categorize()

    assert response.status_code == 200
    Draft202012Validator(load_schema(CATEGORIZE_RESPONSE_SCHEMA)).validate(response.json())


def test_the_response_echoes_the_document_id_it_was_asked_about():
    document_id = str(uuid.uuid4())

    response = post_categorize(document_id=document_id)

    assert response.json()["document_id"] == document_id


def test_the_chosen_category_is_always_one_of_the_given_categories():
    categories = ["Travel", "Office Supplies", "Meals"]

    response = post_categorize(categories=categories)

    assert response.json()["category"] in categories


def test_the_fake_llm_client_is_what_produces_the_response():
    response = post_categorize()

    assert response.json()["model"] == FakeLlmClient.MODEL_NAME


def test_confidence_is_within_range():
    confidence = post_categorize().json()["confidence"]

    assert 0.0 <= confidence <= 1.0


def test_the_citation_is_one_of_the_supplied_policy_chunks_or_null():
    policy_chunks = [{"chunk_text": "Travel expenses over 500 EUR require manager approval."}]

    body = post_categorize(policy_chunks=policy_chunks).json()

    assert body["citation"] in (None, policy_chunks[0]["chunk_text"])


def test_no_policy_chunks_still_produces_a_valid_response_with_a_null_citation():
    response = post_categorize(policy_chunks=[])

    assert response.status_code == 200
    Draft202012Validator(load_schema(CATEGORIZE_RESPONSE_SCHEMA)).validate(response.json())


def test_identical_input_produces_an_identical_response():
    document_id = str(uuid.uuid4())

    first = post_categorize(document_id=document_id).json()
    second = post_categorize(document_id=document_id).json()

    assert first == second


def test_an_empty_category_list_is_rejected_with_422():
    response = post_categorize(categories=[])

    assert response.status_code == 422


def test_a_model_choosing_a_category_outside_the_taxonomy_is_refused():
    class RogueLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "rogue-v1"

        def complete(self, prompt: str) -> str:
            return '{"category": "Not A Real Category", "confidence": 0.9, "citation": null}'

        def complete_vision(self, prompt):
            raise NotImplementedError

    service = CategorizationService(RogueLlmClient())

    with pytest.raises(CategorizationFailedError):
        service.categorize(
            document_id=str(uuid.uuid4()),
            vendor="V",
            currency="EUR",
            total_minor=100,
            document_date="2026-07-14",
            categories=["Travel", "Meals"],
            policy_chunks=[],
        )


def test_a_model_returning_non_json_surfaces_as_a_failure_not_a_crash():
    class NonJsonLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "broken-v1"

        def complete(self, prompt: str) -> str:
            return "not json"

        def complete_vision(self, prompt):
            raise NotImplementedError

    service = CategorizationService(NonJsonLlmClient())

    with pytest.raises(CategorizationFailedError):
        service.categorize(
            document_id=str(uuid.uuid4()),
            vendor="V",
            currency="EUR",
            total_minor=100,
            document_date="2026-07-14",
            categories=["Travel", "Meals"],
            policy_chunks=[],
        )


def test_a_failing_model_surfaces_as_a_failure_not_a_crash():
    class FailingLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "failing-v1"

        def complete(self, prompt: str) -> str:
            raise LlmError("upstream down")

        def complete_vision(self, prompt):
            raise NotImplementedError

    service = CategorizationService(FailingLlmClient())

    with pytest.raises(CategorizationFailedError):
        service.categorize(
            document_id=str(uuid.uuid4()),
            vendor="V",
            currency="EUR",
            total_minor=100,
            document_date="2026-07-14",
            categories=["Travel", "Meals"],
            policy_chunks=[],
        )
