"""`POST /extract` — the `ai` side of the M4 contract."""

import importlib
import json
import uuid

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.config import settings
from app.contracts import EXTRACT_REQUEST_SCHEMA, EXTRACTION_PROPOSAL_SCHEMA, contracts_directory, load_schema
from app.embedded_invoice import EmbeddedInvoiceFields
import app.extraction as extraction_module
from app.extraction import (
    EXTRACTION_INSTRUCTION,
    _UNRECONCILED_LINE_WARNING,
    ExtractionFailedError,
    ExtractionService,
)
from app.llm import FakeLlmClient
from app.llm.client import LlmClient, LlmError, VisionPrompt
from app.main import app

client = TestClient(app)

# Minimal but plausible PDF bytes — comfortably over the stub's readability floor.
PDF_BYTES = b"%PDF-1.7\n" + b"0" * 512 + b"\n%%EOF\n"


def post_extract(content: bytes = PDF_BYTES, content_type: str = "application/pdf", **overrides):
    data = {
        "document_id": overrides.pop("document_id", str(uuid.uuid4())),
        "content_type": content_type,
    }
    data.update(overrides)
    return client.post(
        "/extract",
        files={"file": ("invoice.pdf", content, content_type)},
        data=data,
    )


def test_multipart_upload_returns_200_with_a_schema_valid_body():
    response = post_extract()

    assert response.status_code == 200
    Draft202012Validator(load_schema(EXTRACTION_PROPOSAL_SCHEMA)).validate(response.json())


def test_the_proposal_echoes_the_document_id_it_was_asked_about():
    document_id = str(uuid.uuid4())

    response = post_extract(document_id=document_id)

    assert response.json()["document_id"] == document_id


def test_the_fake_llm_client_is_what_produces_the_proposal():
    response = post_extract()

    assert response.json()["model"] == FakeLlmClient.MODEL_NAME


def test_the_fake_provider_still_resolves_alongside_the_real_adapter():
    """M5 wires a real adapter, but `fake` must keep resolving for tests and offline runs."""
    assert settings.llm_provider == "fake"  # forced by conftest.py for this test process


def test_per_field_confidence_is_present_on_every_extracted_field():
    confidence = post_extract().json()["confidence"]

    for field in ("vendor", "currency", "total_minor", "tax_minor", "document_date"):
        assert field in confidence, f"missing confidence for {field}"
        assert 0.0 <= confidence[field] <= 1.0


def test_every_monetary_value_in_the_response_is_an_integer():
    body = post_extract().json()

    assert isinstance(body["total_minor"], int) and not isinstance(body["total_minor"], bool)
    assert isinstance(body["tax_minor"], int)
    for line in body["lines"]:
        assert isinstance(line["amount_minor"], int)


def test_extraction_instruction_defines_net_line_amounts_and_the_total_invariant():
    assert "pre-tax/net line total" in EXTRACTION_INSTRUCTION
    assert "sum plus tax_minor MUST equal total_minor exactly" in EXTRACTION_INSTRUCTION
    assert "calculate that equality" in EXTRACTION_INSTRUCTION
    assert "complete legal seller/issuer name" in EXTRACTION_INSTRUCTION
    assert "Never substitute an order" in EXTRACTION_INSTRUCTION
    assert "sum of every printed VAT/sales-tax amount" in EXTRACTION_INSTRUCTION


def test_identical_bytes_produce_an_identical_proposal():
    document_id = str(uuid.uuid4())

    first = post_extract(document_id=document_id).json()
    second = post_extract(document_id=document_id).json()

    assert first == second


def test_an_empty_upload_returns_422_not_500():
    response = post_extract(content=b"")

    assert response.status_code == 422
    assert "detail" in response.json()


def test_a_non_document_upload_returns_422_not_500():
    response = post_extract(content=b"hi")

    assert response.status_code == 422


def test_an_unsupported_content_type_returns_422():
    response = post_extract(content_type="application/zip")

    assert response.status_code == 422


def test_supported_content_types_are_derived_from_the_shared_schema():
    from app.main import SUPPORTED_CONTENT_TYPES

    assert SUPPORTED_CONTENT_TYPES == {"application/pdf", "image/jpeg", "image/png"}


def test_an_html_upload_is_rejected_regardless_of_declared_content_type():
    response = post_extract(content_type="text/html")

    assert response.status_code == 422


def test_the_accepted_set_is_genuinely_read_from_the_schema_not_coincidentally_equal(
    tmp_path, monkeypatch
):
    """Rewrite the on-disk schema to drop PNG and prove `main` actually reads it."""
    schema_path = contracts_directory() / EXTRACT_REQUEST_SCHEMA
    original_bytes = schema_path.read_bytes()
    narrowed = json.loads(original_bytes)
    narrowed["properties"]["content_type"]["enum"] = ["application/pdf", "image/jpeg"]

    schema_path.write_text(json.dumps(narrowed), encoding="utf-8")
    try:
        load_schema.cache_clear()
        import app.main as main_module

        importlib.reload(main_module)

        assert "image/png" not in main_module.SUPPORTED_CONTENT_TYPES

        reloaded_client = TestClient(main_module.app)
        response = reloaded_client.post(
            "/extract",
            files={"file": ("invoice.png", PDF_BYTES, "image/png")},
            data={"document_id": str(uuid.uuid4()), "content_type": "image/png"},
            headers={"Authorization": "Bearer test-service-token"},
        )
        assert response.status_code == 422
    finally:
        schema_path.write_bytes(original_bytes)
        load_schema.cache_clear()
        importlib.reload(main_module)


def test_a_schema_missing_the_content_type_enum_fails_loudly_at_import():
    schema_path = contracts_directory() / EXTRACT_REQUEST_SCHEMA
    original_bytes = schema_path.read_bytes()
    broken = json.loads(original_bytes)
    del broken["properties"]["content_type"]["enum"]

    schema_path.write_text(json.dumps(broken), encoding="utf-8")
    try:
        load_schema.cache_clear()
        import app.main as main_module

        with pytest.raises(RuntimeError, match="content_type.enum"):
            importlib.reload(main_module)
    finally:
        schema_path.write_bytes(original_bytes)
        load_schema.cache_clear()
        importlib.reload(main_module)


def test_an_oversized_upload_is_rejected_at_the_configured_cap():
    oversized = b"%PDF-1.7\n" + b"0" * (settings.max_document_bytes + 1)

    response = post_extract(content=oversized)

    assert response.status_code == 413


def test_an_upload_at_exactly_the_cap_is_accepted():
    at_cap = b"%PDF-1.7" + b"0" * (settings.max_document_bytes - 8)
    assert len(at_cap) == settings.max_document_bytes

    response = post_extract(content=at_cap)

    assert response.status_code == 200


def test_a_model_returning_non_json_surfaces_as_extraction_failure_not_a_crash():
    class NonJsonLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "broken-v1"

        def complete(self, prompt: str) -> str:
            return "not json"

        def complete_vision(self, prompt: VisionPrompt) -> str:
            return "definitely not json"

    service = ExtractionService(NonJsonLlmClient())

    with pytest.raises(ExtractionFailedError):
        service.extract(str(uuid.uuid4()), PDF_BYTES, "application/pdf")


def test_a_complete_embedded_ubl_header_overrides_model_header_facts(monkeypatch):
    model_extracted = {
        "vendor": "Issuer from the page",
        "invoice_number": "model-number",
        "currency": "EUR",
        "total_minor": 121,
        "tax_minor": 21,
        "document_date": "2026-07-14",
        "lines": [],
        "confidence": {
            "vendor": 0.8,
            "currency": 0.4,
            "total_minor": 0.4,
            "tax_minor": 0.4,
            "document_date": 0.4,
        },
    }
    monkeypatch.setattr(
        extraction_module,
        "run_extraction_graph",
        lambda *_: {
            "extracted": model_extracted,
            "original_extracted": model_extracted,
            "self_checked_fields": [],
        },
    )
    monkeypatch.setattr(
        extraction_module,
        "extract_embedded_invoice_fields",
        lambda *_: EmbeddedInvoiceFields("ubl-number", "TRY", 1250, 250, "2026-08-01"),
    )

    proposal = ExtractionService(FakeLlmClient()).extract(
        str(uuid.uuid4()), PDF_BYTES, "application/pdf"
    )

    assert proposal["vendor"] == "Issuer from the page"
    assert proposal["invoice_number"] == "ubl-number"
    assert {
        key: proposal[key]
        for key in ("currency", "total_minor", "tax_minor", "document_date")
    } == {
        "currency": "TRY",
        "total_minor": 1250,
        "tax_minor": 250,
        "document_date": "2026-08-01",
    }
    assert all(
        proposal["confidence"][key] == 1.0
        for key in ("currency", "total_minor", "tax_minor", "document_date")
    )


def test_a_model_returning_a_schema_invalid_proposal_is_refused():
    class FloatAmountLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "floaty-v1"

        def complete(self, prompt: str) -> str:
            return "{}"

        def complete_vision(self, prompt: VisionPrompt) -> str:
            # A float amount is exactly what the schema exists to stop.
            return (
                '{"vendor":"V","currency":"EUR","total_minor":121.5,"tax_minor":21,'
                '"document_date":"2026-07-14","lines":[],'
                '"confidence":{"currency":1,"total_minor":1,"tax_minor":1,"document_date":1}}'
            )

    service = ExtractionService(FloatAmountLlmClient())

    with pytest.raises(ExtractionFailedError):
        service.extract(str(uuid.uuid4()), PDF_BYTES, "application/pdf")


def test_a_schema_invalid_self_check_keeps_a_schema_valid_first_proposal():
    class InvalidSelfCheckLlmClient(LlmClient):
        def __init__(self) -> None:
            self.calls = 0

        @property
        def model_name(self) -> str:
            return "self-check-v1"

        def complete(self, prompt: str) -> str:
            raise AssertionError("The extraction path uses vision completion")

        def complete_vision(self, prompt: VisionPrompt) -> str:
            self.calls += 1
            if self.calls == 1:
                return (
                    '{"vendor":"Acme","currency":"EUR","total_minor":121,'
                    '"tax_minor":21,"document_date":"2026-07-14","lines":[],'
                    '"confidence":{"vendor":1,"currency":1,"total_minor":1,'
                    '"tax_minor":1,"document_date":1}}'
                )
            return '{"vendor":"Acme"}'

    proposal = ExtractionService(InvalidSelfCheckLlmClient()).extract(
        str(uuid.uuid4()), PDF_BYTES, "application/pdf"
    )

    assert proposal["total_minor"] == 121


def test_a_failing_model_surfaces_as_extraction_failure():
    class FailingLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "failing-v1"

        def complete(self, prompt: str) -> str:
            raise LlmError("upstream down")

        def complete_vision(self, prompt: VisionPrompt) -> str:
            raise LlmError("upstream down")

    service = ExtractionService(FailingLlmClient())

    with pytest.raises(ExtractionFailedError):
        service.extract(str(uuid.uuid4()), PDF_BYTES, "application/pdf")


def test_the_model_cannot_choose_the_document_id_or_the_model_name():
    class LyingLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "honest-v1"

        def complete(self, prompt: str) -> str:
            return "{}"

        def complete_vision(self, prompt: VisionPrompt) -> str:
            return (
                '{"document_id":"00000000-0000-0000-0000-000000000000",'
                '"model":"claims-to-be-something-else",'
                '"vendor":"V","currency":"EUR","total_minor":121,"tax_minor":21,'
                '"document_date":"2026-07-14","lines":[],'
                '"confidence":{"currency":1,"total_minor":1,"tax_minor":1,"document_date":1}}'
            )

    real_document_id = str(uuid.uuid4())

    proposal = ExtractionService(LyingLlmClient()).extract(
        real_document_id, PDF_BYTES, "application/pdf"
    )

    assert proposal["document_id"] == real_document_id
    assert proposal["model"] == "honest-v1"


def test_unreconciled_lines_are_omitted_without_losing_valid_invoice_level_facts():
    class UnreconciledLinesLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "test-v1"

        def complete(self, prompt: str) -> str:
            return "{}"

        def complete_vision(self, prompt: VisionPrompt) -> str:
            return (
                '{"vendor":"TurkNet","currency":"TRY","total_minor":108377,'
                '"tax_minor":20651,"document_date":"2025-01-27",'
                '"lines":[{"description":"Service","amount_minor":41658},'
                '{"description":"Installation","amount_minor":50000},'
                '{"description":"Discount","amount_minor":-1386}],'
                '"confidence":{"currency":1,"total_minor":1,"tax_minor":1,'
                '"document_date":1}}'
            )

    proposal = ExtractionService(UnreconciledLinesLlmClient()).extract(
        str(uuid.uuid4()), PDF_BYTES, "application/pdf"
    )

    assert proposal["total_minor"] == 108377
    assert proposal["tax_minor"] == 20651
    assert proposal["lines"] == []
    assert proposal["warnings"] == [_UNRECONCILED_LINE_WARNING]


def test_reconciled_lines_are_preserved():
    class ReconciledLinesLlmClient(LlmClient):
        @property
        def model_name(self) -> str:
            return "test-v1"

        def complete(self, prompt: str) -> str:
            return "{}"

        def complete_vision(self, prompt: VisionPrompt) -> str:
            return (
                '{"vendor":"V","currency":"EUR","total_minor":121,'
                '"tax_minor":21,"document_date":"2026-07-14",'
                '"lines":[{"description":"Service","amount_minor":100}],'
                '"confidence":{"currency":1,"total_minor":1,"tax_minor":1,'
                '"document_date":1}}'
            )

    proposal = ExtractionService(ReconciledLinesLlmClient()).extract(
        str(uuid.uuid4()), PDF_BYTES, "application/pdf"
    )

    assert proposal["lines"] == [{"description": "Service", "amount_minor": 100}]
    assert "warnings" not in proposal
