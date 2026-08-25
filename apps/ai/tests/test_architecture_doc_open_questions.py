"""M9.8 T6 -- docs/architecture.md Section 9 ("Open questions") must not carry a row still marked
`Open` once its subject has actually resolved in code. This is a mechanical guard, not a semantic
one: it cannot tell whether a *new* open question was decided, only that none of the ones present
right now silently sit marked Open forever. A future open question is expected and fine; the
failure mode this guards against is a decision landing in code while the doc still claims it's
still pending.
"""

from __future__ import annotations

import re
from pathlib import Path


def _repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "docs" / "architecture.md").is_file():
            return parent
    raise RuntimeError(f"Could not locate docs/architecture.md walking up from {Path(__file__).resolve()}")


def _section_9_table_rows() -> list[str]:
    architecture_doc = (_repo_root() / "docs" / "architecture.md").read_text(encoding="utf-8")
    match = re.search(r"## 9\. Open questions(.*?)(\n## 10\.|\Z)", architecture_doc, re.DOTALL)
    assert match, "docs/architecture.md must have a '## 9. Open questions' section"
    section_9 = match.group(1)
    return [line for line in section_9.splitlines() if line.strip().startswith("| Q")]


def test_no_open_question_row_is_still_marked_open():
    rows = _section_9_table_rows()

    assert rows, "expected at least one Q-row in the Open questions table"

    still_open = [row for row in rows if "| Open |" in row]

    assert not still_open, (
        "docs/architecture.md Section 9 has a row still marked Open -- if its subject has "
        f"resolved in code, update Status to Decided with a rationale paragraph: {still_open}"
    )
