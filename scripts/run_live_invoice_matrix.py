"""Run local invoice PDFs through the real API, LLM, categorization, and ledger pipeline.

Example:
  $env:LEDGERLY_TEST_EMAIL = 'local.invoice.tester@ledgerly.test'
  $env:LEDGERLY_TEST_PASSWORD = '<password>'
  python scripts/run_live_invoice_matrix.py invoices --recursive

The script intentionally treats ``EXTRACTION_NEEDS_REVIEW`` as a failure. An expense that reaches
``NEEDS_REVIEW`` is still a successful extraction outcome: a human approval may be appropriate for
categorization, but the document was not rejected by the extraction trust boundary.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from math import ceil
from pathlib import Path
from statistics import median
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


TERMINAL_DOCUMENT_STATUSES = {"EXTRACTED", "EXTRACTION_NEEDS_REVIEW", "FAILED"}
SUCCESSFUL_EXPENSE_STATUSES = {"POSTED", "NEEDS_REVIEW"}


@dataclass(frozen=True)
class MatrixResult:
    path: Path
    document_status: str
    expense_status: str | None
    elapsed_seconds: float
    activity_seconds: float | None
    reason: str | None

    @property
    def passed(self) -> bool:
        return (
            self.document_status == "EXTRACTED"
            and self.expense_status in SUCCESSFUL_EXPENSE_STATUSES
        )


def json_request(
    method: str,
    url: str,
    headers: dict[str, str] | None = None,
    body: bytes | None = None,
) -> object:
    request = Request(url, data=body, method=method, headers=headers or {})
    try:
        with urlopen(request, timeout=30) as response:
            payload = response.read()
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} returned HTTP {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"{method} {url} could not connect: {error.reason}") from error

    return json.loads(payload) if payload else None


def multipart_upload(path: Path) -> tuple[bytes, str]:
    boundary = f"----ledgerly-{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    body = b"".join((header, path.read_bytes(), f"\r\n--{boundary}--\r\n".encode("utf-8")))
    return body, f"multipart/form-data; boundary={boundary}"


def login(base_url: str, email: str, password: str) -> str:
    response = json_request(
        "POST",
        f"{base_url}/api/v1/auth/login",
        {"Content-Type": "application/json"},
        json.dumps({"email": email, "password": password}).encode("utf-8"),
    )
    if not isinstance(response, dict) or not isinstance(response.get("accessToken"), str):
        raise RuntimeError("Login did not return an access token")
    return response["accessToken"]


def wait_for_document(
    base_url: str, document_id: str, headers: dict[str, str], timeout_seconds: float
) -> dict:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        document = json_request("GET", f"{base_url}/api/v1/documents/{document_id}", headers)
        if isinstance(document, dict) and document.get("status") in TERMINAL_DOCUMENT_STATUSES:
            return document
        time.sleep(0.5)
    raise RuntimeError(f"Document {document_id} did not reach a terminal status in time")


def wait_for_expense(
    base_url: str, document_id: str, headers: dict[str, str], timeout_seconds: float
) -> dict | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        expenses = json_request("GET", f"{base_url}/api/v1/expenses?size=100", headers)
        if isinstance(expenses, list):
            expense = next((item for item in expenses if item.get("documentId") == document_id), None)
            if expense is not None:
                return expense
        time.sleep(0.5)
    return None


def activity_duration_seconds(base_url: str, expense_id: str, headers: dict[str, str]) -> float | None:
    detail = json_request("GET", f"{base_url}/api/v1/expenses/{expense_id}/detail", headers)
    if not isinstance(detail, dict) or not isinstance(detail.get("activity"), list):
        return None
    timestamps = [
        datetime.fromisoformat(item["createdAt"].replace("Z", "+00:00"))
        for item in detail["activity"]
        if isinstance(item, dict) and isinstance(item.get("createdAt"), str)
    ]
    if len(timestamps) < 2:
        return None
    return (max(timestamps) - min(timestamps)).total_seconds()


def run_document(path: Path, base_url: str, headers: dict[str, str], timeout_seconds: float) -> MatrixResult:
    started = time.monotonic()
    body, content_type = multipart_upload(path)
    upload_headers = {
        **headers,
        "Content-Type": content_type,
        "Idempotency-Key": str(uuid.uuid4()),
    }
    uploaded = json_request("POST", f"{base_url}/api/v1/documents", upload_headers, body)
    if not isinstance(uploaded, dict) or not isinstance(uploaded.get("id"), str):
        raise RuntimeError(f"Upload of {path} did not return a document id")

    document = wait_for_document(base_url, uploaded["id"], headers, timeout_seconds)
    elapsed_seconds = time.monotonic() - started
    if document["status"] != "EXTRACTED":
        return MatrixResult(
            path,
            document["status"],
            None,
            elapsed_seconds,
            None,
            document.get("failureReason"),
        )

    expense = wait_for_expense(base_url, uploaded["id"], headers, timeout_seconds)
    if expense is None:
        return MatrixResult(
            path,
            "EXTRACTED",
            None,
            elapsed_seconds,
            None,
            "No expense was created before the timeout",
        )

    activity_seconds = activity_duration_seconds(base_url, expense["id"], headers)
    reason = None
    if expense.get("status") not in SUCCESSFUL_EXPENSE_STATUSES:
        reason = f"Unexpected expense status: {expense.get('status')}"
    return MatrixResult(
        path,
        "EXTRACTED",
        expense.get("status"),
        elapsed_seconds,
        activity_seconds,
        reason,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path, help="PDF files or directories to upload")
    parser.add_argument("--recursive", action="store_true", help="Find PDFs below directory arguments")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--email", default=os.environ.get("LEDGERLY_TEST_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("LEDGERLY_TEST_PASSWORD"))
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    return parser.parse_args()


def input_files(paths: list[Path], recursive: bool) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(path.rglob("*.pdf") if recursive else path.glob("*.pdf"))
        else:
            raise RuntimeError(f"Input path does not exist: {path}")
    unique_files = {path.resolve() for path in files if path.suffix.lower() == ".pdf"}
    if not unique_files:
        raise RuntimeError("No PDF files were found")
    return sorted(unique_files, key=lambda path: str(path).lower())


def main() -> int:
    args = parse_args()
    if not args.email or not args.password:
        raise RuntimeError("Set --email/--password or LEDGERLY_TEST_EMAIL/LEDGERLY_TEST_PASSWORD")

    files = input_files(args.paths, args.recursive)
    base_url = args.base_url.rstrip("/")
    headers = {"Authorization": f"Bearer {login(base_url, args.email, args.password)}"}
    results: list[MatrixResult] = []
    for path in files:
        try:
            result = run_document(path, base_url, headers, args.timeout_seconds)
        except RuntimeError as error:
            result = MatrixResult(path, "REQUEST_FAILED", None, 0.0, None, str(error))
        results.append(result)
        outcome = result.expense_status or result.document_status
        state = "PASS" if result.passed else "FAIL"
        detail = f" ({result.reason})" if result.reason else ""
        print(f"[{state}] {path.name}: {outcome} in {result.elapsed_seconds:.2f}s{detail}")

    passed = [result for result in results if result.passed]
    activity_times = [result.activity_seconds for result in passed if result.activity_seconds is not None]
    print(f"\nSummary: {len(passed)}/{len(results)} passed")
    if activity_times:
        ordered = sorted(activity_times)
        p95_index = max(0, ceil(0.95 * len(ordered)) - 1)
        print(
            "Pipeline activity duration: "
            f"p50={median(ordered):.2f}s p95={ordered[p95_index]:.2f}s max={max(ordered):.2f}s"
        )
    return 0 if len(passed) == len(results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2) from error
