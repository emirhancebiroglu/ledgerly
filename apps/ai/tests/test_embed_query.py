"""`POST /embed-query` — a single embedding vector for api's pgvector retrieval step."""

from jsonschema import Draft202012Validator

from app.contracts import EMBED_QUERY_RESPONSE_SCHEMA, load_schema
from app.embeddings import FakeEmbeddingClient
from app.main import app
from fastapi.testclient import TestClient

client = TestClient(app)


def test_a_query_returns_200_with_a_schema_valid_body():
    response = client.post("/embed-query", json={"text": "Vendor Contoso, total 121.00 EUR"})

    assert response.status_code == 200
    Draft202012Validator(load_schema(EMBED_QUERY_RESPONSE_SCHEMA)).validate(response.json())


def test_the_fake_embedding_client_is_what_produces_the_vector():
    response = client.post("/embed-query", json={"text": "some expense text"})

    assert response.json()["model"] == FakeEmbeddingClient.MODEL_NAME


def test_the_embedding_length_matches_the_declared_dimensions():
    body = client.post("/embed-query", json={"text": "some expense text"}).json()

    assert len(body["embedding"]) == body["embedding_dimensions"]


def test_identical_text_produces_an_identical_embedding():
    first = client.post("/embed-query", json={"text": "repeatable text"}).json()
    second = client.post("/embed-query", json={"text": "repeatable text"}).json()

    assert first == second


def test_an_empty_text_is_rejected_with_422():
    response = client.post("/embed-query", json={"text": ""})

    assert response.status_code == 422
