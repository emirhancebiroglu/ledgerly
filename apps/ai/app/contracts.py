"""Access to the shared contract schemas in ``docs/contracts/``.

That directory is the single source of truth for the ``api`` <-> ``ai`` interface. Nothing here
restates a field list: the schemas are read from disk, so a change on one side that breaks the
other fails a contract test rather than being discovered in production.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any


class ContractsNotFoundError(RuntimeError):
    """The shared contracts directory could not be located."""


def contracts_directory() -> Path:
    """Walk up from this file to the repository root and return ``docs/contracts``.

    Searching for the directory rather than assuming a fixed relative depth means a moved module
    fails loudly here instead of silently validating against nothing.
    """
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "docs" / "contracts"
        if candidate.is_dir():
            return candidate
    raise ContractsNotFoundError(
        f"Could not locate docs/contracts walking up from {Path(__file__).resolve()}"
    )


@lru_cache(maxsize=None)
def load_schema(name: str) -> dict[str, Any]:
    """Load a schema by filename, e.g. ``extraction-proposal.schema.json``."""
    schema_path = contracts_directory() / name
    if not schema_path.is_file():
        raise ContractsNotFoundError(f"Missing contract schema: {schema_path}")
    return json.loads(schema_path.read_text(encoding="utf-8"))


def load_example(name: str) -> dict[str, Any]:
    """Load a golden example by filename from ``docs/contracts/examples``."""
    example_path = contracts_directory() / "examples" / name
    if not example_path.is_file():
        raise ContractsNotFoundError(f"Missing contract example: {example_path}")
    return json.loads(example_path.read_text(encoding="utf-8"))


EXTRACTION_PROPOSAL_SCHEMA = "extraction-proposal.schema.json"
EXTRACT_REQUEST_SCHEMA = "extract-request.schema.json"
EMBED_POLICY_REQUEST_SCHEMA = "embed-policy-request.schema.json"
EMBED_POLICY_RESPONSE_SCHEMA = "embed-policy-response.schema.json"
