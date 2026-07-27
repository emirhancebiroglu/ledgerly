"""Loads eval fixtures from ``evals/fixtures/{real,public,synthetic}/*.json``.

Each fixture JSON has:
  - ``source``: path to the document, relative to the repo root. Real fixtures point into the
    gitignored top-level ``invoices/`` directory; public/synthetic fixtures point at a PDF
    committed alongside the fixture itself.
  - ``content_type``: the media type to send, mirroring what `api`'s magic-byte detection would
    produce for this file.
  - ``expected``: the hand-written expected output, in the shape of an ``ExtractionProposal``
    minus ``document_id``, ``model`` and ``confidence`` — those are either supplied at eval time
    or not something a fixture can know in advance.
  - ``layout_note``: why this fixture exists / what it exercises. Not used by the harness; kept
    for humans reading the fixture.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
GROUPS = ("real", "public", "synthetic")


class FixtureError(RuntimeError):
    """A fixture is missing, malformed, or its source document is unavailable."""


@dataclass(frozen=True)
class Fixture:
    name: str
    group: str
    source_path: Path
    content_type: str
    expected: dict

    @property
    def source_available(self) -> bool:
        return self.source_path.is_file()

    def read_bytes(self) -> bytes:
        if not self.source_available:
            raise FixtureError(f"Fixture source not found: {self.source_path}")
        return self.source_path.read_bytes()


def load_fixtures(groups: tuple[str, ...] = GROUPS) -> list[Fixture]:
    fixtures = []
    for group in groups:
        group_dir = FIXTURES_DIR / group
        if not group_dir.is_dir():
            continue
        for fixture_file in sorted(group_dir.glob("*.json")):
            data = json.loads(fixture_file.read_text(encoding="utf-8"))
            fixtures.append(
                Fixture(
                    name=f"{group}/{fixture_file.stem}",
                    group=group,
                    source_path=(REPO_ROOT / data["source"]).resolve(),
                    content_type=data["content_type"],
                    expected=data["expected"],
                )
            )
    return fixtures
