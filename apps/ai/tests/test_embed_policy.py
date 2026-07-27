"""`POST /embed-policy` — the M6 policy chunk+embed endpoint."""

import uuid

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.config import settings
from app.contracts import EMBED_POLICY_RESPONSE_SCHEMA, load_schema
from app.embeddings import FakeEmbeddingClient
from app.embeddings.client import EmbeddingClient
from app.main import app
from app.policy.embedding import PolicyEmbeddingFailedError, PolicyEmbeddingService
from tests.pdf_fixtures import make_text_pdf

client = TestClient(app)

POLICY_TEXT = (
    "Travel expenses over 500 EUR require manager approval.\n"
    "Meals are reimbursed up to 50 EUR per day while travelling."
)
POLICY_PDF = make_text_pdf(POLICY_TEXT)


def post_embed_policy(content: bytes = POLICY_PDF, content_type: str = "application/pdf", **overrides):
    data = {
        "policy_document_id": overrides.pop("policy_document_id", str(uuid.uuid4())),
        "content_type": content_type,
    }
    data.update(overrides)
    return client.post(
        "/embed-policy",
        files={"file": ("policy.pdf", content, content_type)},
        data=data,
    )


def test_a_real_policy_pdf_returns_200_with_a_schema_valid_body():
    response = post_embed_policy()

    assert response.status_code == 200
    Draft202012Validator(load_schema(EMBED_POLICY_RESPONSE_SCHEMA)).validate(response.json())


def test_the_response_echoes_the_policy_document_id_it_was_asked_about():
    policy_document_id = str(uuid.uuid4())

    response = post_embed_policy(policy_document_id=policy_document_id)

    assert response.json()["policy_document_id"] == policy_document_id


def test_the_fake_embedding_client_is_what_produces_the_response():
    response = post_embed_policy()

    assert response.json()["model"] == FakeEmbeddingClient.MODEL_NAME


def test_every_chunk_carries_an_embedding_of_the_declared_dimensionality():
    body = post_embed_policy().json()

    for chunk in body["chunks"]:
        assert len(chunk["embedding"]) == body["embedding_dimensions"]


def test_chunk_indices_are_zero_based_and_sequential():
    body = post_embed_policy().json()

    assert [chunk["chunk_index"] for chunk in body["chunks"]] == list(range(len(body["chunks"])))


def test_identical_bytes_produce_identical_embeddings():
    policy_document_id = str(uuid.uuid4())

    first = post_embed_policy(policy_document_id=policy_document_id).json()
    second = post_embed_policy(policy_document_id=policy_document_id).json()

    assert first == second


def test_a_pdf_with_no_extractable_text_returns_422_not_500():
    blank_pdf = make_text_pdf("")

    response = post_embed_policy(content=blank_pdf)

    assert response.status_code == 422
    assert "detail" in response.json()


def test_bytes_that_are_not_a_real_pdf_return_422_not_500():
    fake_pdf = b"%PDF-1.7\n" + b"0" * 512 + b"\n%%EOF\n"

    response = post_embed_policy(content=fake_pdf)

    assert response.status_code == 422


def test_an_unsupported_content_type_returns_422():
    response = post_embed_policy(content_type="image/png")

    assert response.status_code == 422


def test_an_oversized_upload_is_rejected_at_the_configured_cap():
    oversized = POLICY_PDF + b"0" * settings.max_document_bytes

    response = post_embed_policy(content=oversized)

    assert response.status_code == 413


def test_a_failing_embedding_client_surfaces_as_a_failure_not_a_crash():
    class FailingEmbeddingClient(EmbeddingClient):
        @property
        def model_name(self) -> str:
            return "failing-embedder"

        @property
        def dimensions(self) -> int:
            return 8

        def embed(self, texts: list[str]) -> list[list[float]]:
            raise RuntimeError("upstream down")

    service = PolicyEmbeddingService(FailingEmbeddingClient())

    with pytest.raises(RuntimeError):
        service.embed_policy(str(uuid.uuid4()), POLICY_PDF)


def test_a_long_document_is_split_into_multiple_chunks():
    long_text = "\n".join(f"Policy clause number {i}: reimbursement rule text." for i in range(200))
    long_pdf = make_text_pdf(long_text)

    response = post_embed_policy(content=long_pdf)

    assert response.status_code == 200
    assert len(response.json()["chunks"]) > 1
