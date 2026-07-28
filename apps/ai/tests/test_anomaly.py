"""M8 T3 anomaly contract, deterministic statistics, and explanation boundary."""

import uuid

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.anomaly.anomaly import AnomalyService, calculate_statistics
from app.contracts import (
    ANOMALY_REQUEST_SCHEMA,
    ANOMALY_RESPONSE_SCHEMA,
    load_example,
    load_schema,
)
from app.llm.client import LlmClient
from app.main import app

client = TestClient(app)


def anomaly_body(**overrides):
    body = {
        "expense_id": str(uuid.uuid4()),
        "category_id": str(uuid.uuid4()),
        "currency": "EUR",
        "amount_minor": 300,
        "history": [
            {"amount_minor": 100, "posted_at": "2026-07-01T10:00:00Z"},
            {"amount_minor": 200, "posted_at": "2026-07-10T10:00:00Z"},
        ],
        "budget": {"period": "2026-07", "limit_minor": 1_000, "spent_minor": 500},
    }
    body.update(overrides)
    return body


def test_anomaly_contracts_and_golden_examples_validate():
    for name, example in (
        (ANOMALY_REQUEST_SCHEMA, "anomaly-request.valid.json"),
        (ANOMALY_RESPONSE_SCHEMA, "anomaly-response.valid.json"),
    ):
        Draft202012Validator.check_schema(load_schema(name))
        Draft202012Validator(load_schema(name)).validate(load_example(example))


def test_contract_rejects_float_money_and_unknown_fields():
    validator = Draft202012Validator(load_schema(ANOMALY_REQUEST_SCHEMA))
    float_money = load_example("anomaly-request.valid.json") | {"amount_minor": 12.5}
    unknown_field = load_example("anomaly-request.valid.json") | {"surprise": True}

    assert list(validator.iter_errors(float_money))
    assert list(validator.iter_errors(unknown_field))


def test_sparse_and_zero_variance_history_produce_finite_low_risk_statistics():
    sparse = calculate_statistics(500, [100], None)
    zero_variance = calculate_statistics(500, [100, 100, 100], None)

    assert sparse.z_score == 0.0
    assert zero_variance.z_score == 0.0
    assert sparse.risk == zero_variance.risk == "LOW"


def test_high_boundary_is_inclusive_and_deterministic():
    statistics = calculate_statistics(300, [100, 200], None)

    assert statistics.z_score == 3.0
    assert statistics.risk == "HIGH"


def test_endpoint_returns_schema_valid_code_derived_facts():
    response = client.post("/anomaly", json=anomaly_body())

    assert response.status_code == 200
    Draft202012Validator(load_schema(ANOMALY_RESPONSE_SCHEMA)).validate(response.json())
    assert response.json()["risk"] == "HIGH"
    assert response.json()["z_score"] == 3.0
    assert response.json()["budget_burn_rate"] == 0.5


def test_endpoint_rejects_float_money_and_unknown_fields():
    assert client.post("/anomaly", json=anomaly_body(amount_minor=300.0)).status_code == 422
    assert client.post("/anomaly", json=anomaly_body(extra="nope")).status_code == 422
    numeric_timestamp = anomaly_body()
    numeric_timestamp["history"][0]["posted_at"] = 0
    assert client.post("/anomaly", json=numeric_timestamp).status_code == 422


def test_model_cannot_surface_an_invented_number():
    class NumericLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "numeric-test"

        def complete(self, prompt: str) -> str:
            return '{"explanation": "This expense is 999 standard deviations from normal."}'

        def complete_vision(self, prompt):
            raise NotImplementedError

    response = AnomalyService(NumericLlmClient()).analyze(
        expense_id=str(uuid.uuid4()),
        amount_minor=300,
        history=[{"amount_minor": 100}, {"amount_minor": 200}],
        budget=None,
    )

    assert "999" not in response["explanation"]
    assert response["z_score"] == 3.0
