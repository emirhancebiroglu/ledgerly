from __future__ import annotations

import hashlib
import json
from http.client import IncompleteRead, RemoteDisconnected
from pathlib import Path

import pytest

from scripts.run_invoice_quality_matrix import (
    document_activity_stages,
    expense_field_values,
    json_request,
    mismatched_fields,
    load_manifest,
    wait_for_api,
)


def write_pdf(path: Path, content: bytes) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return hashlib.sha256(content).hexdigest()


def manifest_entry(path: str, digest: str, outcome: str = "requires_expense") -> dict:
    entry = {"path": path, "sha256": digest, "outcome": outcome}
    if outcome != "invalid_upload":
        entry["expected"] = {
            "vendor": "Acme GmbH",
            "currency": "EUR",
            "total_minor": 1210,
            "tax_minor": 210,
            "document_date": "2026-07-14",
            "invoice_number": "INV-42",
        }
    return entry


def write_manifest(path: Path, entries: list[dict]) -> None:
    path.write_text(json.dumps({"version": 1, "entries": entries}), encoding="utf-8")


def test_manifest_accounts_for_every_pdf_by_hash(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    first = write_pdf(root / "real" / "first.pdf", b"%PDF-first")
    second = write_pdf(root / "public" / "second.PDF", b"%PDF-second")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(
        manifest,
        [manifest_entry("real/first.pdf", first), manifest_entry("public/second.PDF", second)],
    )

    entries = load_manifest(manifest, root)

    assert [entry.sha256 for entry in entries] == [first, second]


def test_manifest_rejects_an_unaccounted_corpus_pdf(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    digest = write_pdf(root / "first.pdf", b"%PDF-first")
    write_pdf(root / "second.pdf", b"%PDF-second")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(manifest, [manifest_entry("first.pdf", digest)])

    with pytest.raises(ValueError, match="every corpus PDF"):
        load_manifest(manifest, root)


def test_manifest_rejects_a_path_hash_mismatch(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    first = write_pdf(root / "first.pdf", b"%PDF-first")
    second = write_pdf(root / "second.pdf", b"%PDF-second")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(manifest, [manifest_entry("first.pdf", second), manifest_entry("second.pdf", first)])

    with pytest.raises(ValueError, match="path and SHA-256"):
        load_manifest(manifest, root)


def test_manifest_rejects_a_traversal_path_even_when_it_resolves_inside_the_corpus(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    digest = write_pdf(root / "first.pdf", b"%PDF-first")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(manifest, [manifest_entry("../invoices/first.pdf", digest)])

    with pytest.raises(ValueError, match="must stay below the corpus root"):
        load_manifest(manifest, root)


def test_manifest_rejects_duplicate_hashes(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    digest = write_pdf(root / "first.pdf", b"%PDF-first")
    second = write_pdf(root / "second.pdf", b"%PDF-second")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(manifest, [manifest_entry("first.pdf", digest), manifest_entry("second.pdf", digest)])

    with pytest.raises(ValueError, match="duplicate PDF hashes"):
        load_manifest(manifest, root)


def test_manifest_rejects_an_entry_missing_required_core_fields(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    digest = write_pdf(root / "first.pdf", b"%PDF-first")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(
        manifest,
        [{"path": "first.pdf", "sha256": digest, "outcome": "requires_expense", "expected": {}}],
    )

    with pytest.raises(ValueError, match="require core expected fields"):
        load_manifest(manifest, root)


def test_manifest_accepts_a_supported_document_that_must_be_reviewed(tmp_path: Path) -> None:
    root = tmp_path / "invoices"
    digest = write_pdf(root / "old-invoice.pdf", b"%PDF-old")
    manifest = tmp_path / "quality-manifest.json"
    write_manifest(
        manifest,
        [manifest_entry("old-invoice.pdf", digest, outcome="extraction_needs_review")],
    )

    entries = load_manifest(manifest, root)

    assert entries[0].outcome == "extraction_needs_review"


def test_api_readiness_timeout_is_an_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("scripts.run_invoice_quality_matrix.json_request", lambda *_args, **_kwargs: {})
    monkeypatch.setattr("scripts.run_invoice_quality_matrix.time.sleep", lambda _seconds: None)

    with pytest.raises(RuntimeError, match="did not become healthy"):
        wait_for_api("http://localhost:8080", timeout_seconds=-1)


def test_json_request_converts_a_restart_disconnect_to_a_retryable_runtime_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def disconnect(*_args: object, **_kwargs: object) -> None:
        raise RemoteDisconnected("container restarted")

    monkeypatch.setattr("scripts.run_invoice_quality_matrix.urlopen", disconnect)

    with pytest.raises(RuntimeError, match="disconnected before a response"):
        json_request("GET", "http://localhost:8080/actuator/health")


def test_json_request_converts_an_aborted_restart_connection_to_a_retryable_runtime_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def abort(*_args: object, **_kwargs: object) -> None:
        raise ConnectionAbortedError("container restarted")

    monkeypatch.setattr("scripts.run_invoice_quality_matrix.urlopen", abort)

    with pytest.raises(RuntimeError, match="disconnected before a response"):
        json_request("GET", "http://localhost:8080/actuator/health")


def test_expense_field_mismatch_reports_only_the_field_names() -> None:
    observed = expense_field_values(
        {
            "vendor": "Acme GmbH",
            "currency": "EUR",
            "amountMinor": "1210",
            "taxMinor": "210",
            "documentDate": "2026-07-14",
            "invoiceNumber": None,
        }
    )

    assert mismatched_fields(
        observed,
        {"vendor": "Acme GmbH", "invoice_number": "INV-42"},
    ) == ["invoice_number"]


def test_activity_stream_parser_collects_only_activity_stages(monkeypatch: pytest.MonkeyPatch) -> None:
    payload = b": keepalive\n\nevent: activity\ndata: {\"stage\":\"EXTRACTING\"}\n\ndata: {\"stage\":\"NO_POSTING_REQUIRED\"}\n"

    class Response:
        def read(self) -> bytes:
            raise IncompleteRead(payload)

        def __enter__(self) -> "Response":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

    monkeypatch.setattr("scripts.run_invoice_quality_matrix.urlopen", lambda *_args, **_kwargs: Response())

    assert document_activity_stages("http://api", "document-id", {}, 1) == {
        "EXTRACTING",
        "NO_POSTING_REQUIRED",
    }
