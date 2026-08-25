"""M9.8 T6 -- docs/architecture.md's Section 2 (`ai` service boundary) claims to list every
endpoint `ai` exposes. This test reads the real FastAPI route table from `app.main.app` and
asserts every documented HTTP verb + path pair actually appears in that section of the doc, so
adding a route without documenting it fails a test instead of quietly drifting from what the
architecture document claims the system does.

`/health` is deliberately excluded: it is infrastructure (liveness), not part of the `ai`
capability list architecture.md Section 2 documents; every capability route ai actually exposes
must be covered.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.main import app

EXCLUDED_PATHS = {"/health"}


def _repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "docs" / "architecture.md").is_file():
            return parent
    raise RuntimeError(f"Could not locate docs/architecture.md walking up from {Path(__file__).resolve()}")


def _section_2_text() -> str:
    architecture_doc = (_repo_root() / "docs" / "architecture.md").read_text(encoding="utf-8")
    match = re.search(r"## 2\. Service boundaries(.*?)\n## 3\.", architecture_doc, re.DOTALL)
    assert match, "docs/architecture.md must have a '## 2. Service boundaries' section"
    return match.group(1)


def _real_ai_routes() -> set[tuple[str, str]]:
    routes: set[tuple[str, str]] = set()
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if path is None or methods is None or path in EXCLUDED_PATHS:
            continue
        for method in methods:
            if method == "HEAD":  # FastAPI adds this automatically alongside GET; not a real route.
                continue
            routes.add((method, path))
    return routes


def test_every_ai_route_is_documented_in_architecture_md_section_2():
    section_2 = _section_2_text()
    real_routes = _real_ai_routes()

    assert real_routes, "expected at least one real ai route to check against the doc"

    undocumented = [
        (method, path)
        for method, path in real_routes
        if f"`{method} {path}`" not in section_2
    ]

    assert not undocumented, (
        "docs/architecture.md Section 2 is missing these ai routes (add them, in the same "
        f"`METHOD /path` backtick form the section already uses): {sorted(undocumented)}"
    )
