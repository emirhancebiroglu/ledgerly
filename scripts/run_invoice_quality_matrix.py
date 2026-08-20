"""Run the local invoice corpus through the complete Ledgerly pipeline.

The corpus and its ground truth live under the gitignored ``invoices/`` directory. This script
never prints document content, filenames, expected field values, or credentials: local invoices
can contain personal and financial data. It reports case numbers and aggregate evidence only.

Example:
    python scripts/run_invoice_quality_matrix.py invoices/quality-manifest.json --report-only
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from statistics import median
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


TERMINAL_DOCUMENT_STATUSES = {"EXTRACTED", "EXTRACTION_NEEDS_REVIEW", "FAILED"}
SUCCESSFUL_EXPENSE_STATUSES = {"POSTED", "NEEDS_REVIEW"}
EXPECTED_FIELDS = {
    "vendor",
    "currency",
    "total_minor",
    "tax_minor",
    "document_date",
    "invoice_number",
}
ENTRY_OUTCOMES = {
    "requires_expense",
    "no_posting_required",
    "extraction_needs_review",
    "invalid_upload",
}


@dataclass(frozen=True)
class CorpusEntry:
    path: Path
    sha256: str
    outcome: str
    expected: dict[str, Any]
    risk_case: bool


@dataclass(frozen=True)
class QualityResult:
    case_number: int
    passed: bool
    outcome: str
    elapsed_seconds: float
    reason: str | None = None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def corpus_pdf_paths(corpus_root: Path) -> dict[str, Path]:
    if not corpus_root.is_dir():
        raise ValueError("Corpus root does not exist")

    by_hash: dict[str, Path] = {}
    for path in corpus_root.rglob("*"):
        if not path.is_file() or path.suffix.lower() != ".pdf":
            continue
        digest = sha256_file(path)
        if digest in by_hash:
            raise ValueError("Corpus contains duplicate PDF content hashes")
        by_hash[digest] = path.resolve()
    if not by_hash:
        raise ValueError("Corpus contains no PDF files")
    return by_hash


def load_manifest(manifest_path: Path, corpus_root: Path) -> list[CorpusEntry]:
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError("Quality manifest does not exist") from error
    except json.JSONDecodeError as error:
        raise ValueError("Quality manifest is not valid JSON") from error

    if not isinstance(payload, dict) or payload.get("version") != 1:
        raise ValueError("Quality manifest must have version 1")
    raw_entries = payload.get("entries")
    if not isinstance(raw_entries, list) or not raw_entries:
        raise ValueError("Quality manifest must contain entries")

    corpus_root = corpus_root.resolve()
    corpus_by_hash = corpus_pdf_paths(corpus_root)
    entries: list[CorpusEntry] = []
    seen_hashes: set[str] = set()
    seen_paths: set[Path] = set()
    for raw_entry in raw_entries:
        if not isinstance(raw_entry, dict):
            raise ValueError("Every quality manifest entry must be an object")
        relative_path = raw_entry.get("path")
        digest = raw_entry.get("sha256")
        outcome = raw_entry.get("outcome")
        expected = raw_entry.get("expected", {})
        if not isinstance(relative_path, str) or not relative_path:
            raise ValueError("Quality manifest entry path is required")
        if not isinstance(digest, str) or len(digest) != 64 or any(
            character not in "0123456789abcdef" for character in digest
        ):
            raise ValueError("Quality manifest entry sha256 must be lowercase SHA-256")
        if outcome not in ENTRY_OUTCOMES:
            raise ValueError("Quality manifest entry outcome is invalid")
        if not isinstance(expected, dict) or not set(expected).issubset(EXPECTED_FIELDS):
            raise ValueError("Quality manifest entry expected fields are invalid")
        if outcome != "invalid_upload" and not {"currency", "total_minor", "tax_minor", "document_date"}.issubset(expected):
            raise ValueError("Supported invoice entries require core expected fields")
        if digest in seen_hashes:
            raise ValueError("Quality manifest contains duplicate PDF hashes")

        relative = Path(relative_path)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Quality manifest entry path must stay below the corpus root")
        candidate = (corpus_root / relative).resolve()
        if candidate.parent != corpus_root and corpus_root not in candidate.parents:
            raise ValueError("Quality manifest entry path escapes the corpus root")
        if candidate in seen_paths:
            raise ValueError("Quality manifest contains duplicate PDF paths")
        if corpus_by_hash.get(digest) != candidate:
            raise ValueError("Quality manifest path and SHA-256 do not match the corpus")

        entries.append(
            CorpusEntry(
                path=candidate,
                sha256=digest,
                outcome=outcome,
                expected=expected,
                risk_case=raw_entry.get("risk_case") is True,
            )
        )
        seen_hashes.add(digest)
        seen_paths.add(candidate)

    if set(corpus_by_hash) != seen_hashes:
        raise ValueError("Quality manifest does not account for every corpus PDF exactly once")
    return entries


def json_request(
    method: str, url: str, headers: dict[str, str] | None = None, body: bytes | None = None
) -> Any:
    request = Request(url, data=body, method=method, headers=headers or {})
    try:
        with urlopen(request, timeout=30) as response:
            payload = response.read()
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} request failed with HTTP {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"{method} request could not connect: {error.reason}") from error
    return json.loads(payload) if payload else None


def multipart_upload(path: Path) -> tuple[bytes, str]:
    boundary = f"----ledgerly-quality-{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="upload.pdf"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    body = b"".join((header, path.read_bytes(), f"\r\n--{boundary}--\r\n".encode("utf-8")))
    return body, f"multipart/form-data; boundary={boundary}"


def create_test_account(base_url: str) -> str:
    suffix = uuid.uuid4().hex
    response = json_request(
        "POST",
        f"{base_url}/api/v1/auth/register",
        {"Content-Type": "application/json"},
        json.dumps(
            {
                "fullName": "Invoice Quality Runner",
                "company": f"invoice-quality-{suffix}",
                "email": f"invoice-quality-{suffix}@ledgerly.test",
                "password": "invoice-quality-runner",
            }
        ).encode("utf-8"),
    )
    if not isinstance(response, dict) or not isinstance(response.get("accessToken"), str):
        raise RuntimeError("Registration did not return an access token")
    return response["accessToken"]


def wait_for_document(
    base_url: str, document_id: str, headers: dict[str, str], timeout_seconds: float
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        document = json_request("GET", f"{base_url}/api/v1/documents/{document_id}", headers)
        if isinstance(document, dict) and document.get("status") in TERMINAL_DOCUMENT_STATUSES:
            return document
        time.sleep(0.5)
    raise RuntimeError("Document did not reach a terminal extraction status")


def wait_for_expense(
    base_url: str, document_id: str, headers: dict[str, str], timeout_seconds: float
) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        expenses = json_request("GET", f"{base_url}/api/v1/expenses?size=100", headers)
        if isinstance(expenses, list):
            expense = next((item for item in expenses if item.get("documentId") == document_id), None)
            if isinstance(expense, dict):
                return expense
        time.sleep(0.5)
    return None


def expense_field_values(detail: dict[str, Any]) -> dict[str, Any]:
    return {
        "vendor": detail.get("vendor"),
        "currency": detail.get("currency"),
        "total_minor": int(detail["amountMinor"]) if isinstance(detail.get("amountMinor"), str) else detail.get("amountMinor"),
        "tax_minor": int(detail["taxMinor"]) if isinstance(detail.get("taxMinor"), str) else detail.get("taxMinor"),
        "document_date": detail.get("documentDate"),
        "invoice_number": detail.get("invoiceNumber"),
    }


def proposal_field_values(proposal: dict[str, Any]) -> dict[str, Any]:
    return {
        "vendor": proposal.get("vendor"),
        "currency": proposal.get("currency"),
        "total_minor": proposal.get("total_minor"),
        "tax_minor": proposal.get("tax_minor"),
        "document_date": proposal.get("document_date"),
        "invoice_number": proposal.get("invoice_number"),
    }


def mismatched_fields(observed: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    return [field for field, value in expected.items() if observed.get(field) != value]


def run_invoice(
    case_number: int,
    entry: CorpusEntry,
    base_url: str,
    headers: dict[str, str],
    timeout_seconds: float,
) -> QualityResult:
    started = time.monotonic()
    try:
        body, content_type = multipart_upload(entry.path)
        try:
            uploaded = json_request(
                "POST",
                f"{base_url}/api/v1/documents",
                {**headers, "Content-Type": content_type, "Idempotency-Key": str(uuid.uuid4())},
                body,
            )
        except RuntimeError as error:
            if entry.outcome == "invalid_upload" and "HTTP 4" in str(error):
                return QualityResult(case_number, True, "rejected", time.monotonic() - started)
            raise

        if entry.outcome == "invalid_upload":
            return QualityResult(case_number, False, "accepted-invalid-upload", time.monotonic() - started)
        if not isinstance(uploaded, dict) or not isinstance(uploaded.get("id"), str):
            raise RuntimeError("Upload did not return a document identifier")

        document_id = uploaded["id"]
        document = wait_for_document(base_url, document_id, headers, timeout_seconds)
        if entry.outcome == "extraction_needs_review":
            proposal = document.get("proposal")
            if isinstance(proposal, str):
                proposal = json.loads(proposal)
            if document.get("status") != "EXTRACTION_NEEDS_REVIEW":
                return QualityResult(case_number, False, "unexpected-extraction-outcome", time.monotonic() - started)
            if not isinstance(proposal, dict):
                return QualityResult(case_number, False, "missing-review-proposal", time.monotonic() - started)
            mismatches = mismatched_fields(proposal_field_values(proposal), entry.expected)
            if not mismatches:
                return QualityResult(case_number, True, "extraction-needs-review", time.monotonic() - started)
            return QualityResult(
                case_number,
                False,
                f"review-field-mismatch:{','.join(mismatches)}",
                time.monotonic() - started,
            )
        if document.get("status") != "EXTRACTED":
            return QualityResult(case_number, False, str(document.get("status")), time.monotonic() - started)

        expense = wait_for_expense(base_url, document_id, headers, timeout_seconds)
        if entry.outcome == "no_posting_required":
            if expense is None:
                return QualityResult(case_number, False, "missing-no-posting-outcome", time.monotonic() - started)
            return QualityResult(case_number, False, "unexpected-expense", time.monotonic() - started)
        if expense is None:
            return QualityResult(case_number, False, "missing-expense", time.monotonic() - started)
        if expense.get("status") not in SUCCESSFUL_EXPENSE_STATUSES:
            return QualityResult(case_number, False, "unexpected-expense-status", time.monotonic() - started)

        detail = json_request("GET", f"{base_url}/api/v1/expenses/{expense['id']}/detail", headers)
        if not isinstance(detail, dict):
            return QualityResult(case_number, False, "missing-expense-detail", time.monotonic() - started)
        mismatches = mismatched_fields(expense_field_values(detail), entry.expected)
        if mismatches:
            return QualityResult(
                case_number,
                False,
                f"field-mismatch:{','.join(mismatches)}",
                time.monotonic() - started,
            )
        return QualityResult(case_number, True, str(expense["status"]), time.monotonic() - started)
    except RuntimeError as error:
        return QualityResult(case_number, False, "request-failed", time.monotonic() - started, str(error))


def compose_up(repo_root: Path) -> None:
    env_file = repo_root / ".env"
    compose_file = repo_root / "infra" / "docker-compose.yml"
    if not env_file.is_file() or not compose_file.is_file():
        raise RuntimeError("Compose environment or configuration is missing")
    environment = os.environ.copy()
    environment.setdefault("LOADTEST_DOCUMENT_QUOTA", "1000")
    environment.setdefault("LOADTEST_AI_QUOTA", "1000")
    subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            str(env_file),
            "-f",
            str(compose_file),
            "up",
            "--build",
            "-d",
        ],
        check=True,
        env=environment,
    )


def wait_for_api(base_url: str, timeout_seconds: float) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            health = json_request("GET", f"{base_url}/actuator/health")
            if isinstance(health, dict) and health.get("status") == "UP":
                return
        except RuntimeError:
            pass
        time.sleep(1)
    raise RuntimeError("API did not become healthy before the quality run")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--corpus-root", type=Path, default=Path("invoices"))
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    parser.add_argument("--compose-build", action="store_true")
    parser.add_argument("--report-only", action="store_true")
    parser.add_argument("--repeat-risk-cases", type=int, default=1)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.timeout_seconds <= 0 or args.repeat_risk_cases <= 0:
        raise RuntimeError("Timeout and risk repetition count must be positive")
    repo_root = Path(__file__).resolve().parents[1]
    base_url = args.base_url.rstrip("/")
    if args.compose_build:
        compose_up(repo_root)

    entries = load_manifest(args.manifest, args.corpus_root)
    wait_for_api(base_url, args.timeout_seconds)
    headers = {"Authorization": f"Bearer {create_test_account(base_url)}"}
    runs = [entry for entry in entries if not entry.risk_case]
    risk_cases = [entry for entry in entries if entry.risk_case]
    runs.extend(entry for _ in range(args.repeat_risk_cases) for entry in risk_cases)

    results = [
        run_invoice(index, entry, base_url, headers, args.timeout_seconds)
        for index, entry in enumerate(runs, start=1)
    ]
    for result in results:
        state = "PASS" if result.passed else "FAIL"
        print(f"[{state}] case {result.case_number}: {result.outcome} in {result.elapsed_seconds:.2f}s")

    passed = [result for result in results if result.passed]
    latencies = [result.elapsed_seconds for result in passed]
    print(f"Summary: {len(passed)}/{len(results)} passed")
    if latencies:
        ordered = sorted(latencies)
        p95_index = max(0, (len(ordered) * 95 + 99) // 100 - 1)
        print(f"Latency: p50={median(ordered):.2f}s p95={ordered[p95_index]:.2f}s")
    return 0 if args.report_only or len(passed) == len(results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2) from error
