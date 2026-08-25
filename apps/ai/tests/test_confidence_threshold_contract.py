"""M9.8 T5 -- docs/contracts/thresholds.json is the single source of truth for the confidence
threshold that gates both api's categorization posting decision and ai's extraction self-check
retry. This file and the api side's ``ConfidenceThresholdContractTest`` read the very same
thresholds.json, so a value edited on one side without the other fails one of these suites
instead of silently diverging in production.
"""

import pytest

from app import contracts
from app.contracts import ContractsNotFoundError, load_thresholds
from app.llm.extraction_graph import CONFIDENCE_THRESHOLD


def test_extraction_graphs_confidence_threshold_matches_the_shared_contract():
    contract = load_thresholds()

    assert CONFIDENCE_THRESHOLD == contract["confidenceThreshold"], (
        "app.llm.extraction_graph.CONFIDENCE_THRESHOLD has drifted from "
        "docs/contracts/thresholds.json's confidenceThreshold -- api's "
        "ledgerly.categorization.confidence-threshold must change together with this value"
    )


def test_a_missing_thresholds_file_fails_loudly_rather_than_defaulting(tmp_path, monkeypatch):
    """A silently-guessed threshold changes which extractions/categorizations post without
    anyone deciding that on purpose -- this must be a loud failure, never a skip or a fallback."""
    empty_contracts_dir = tmp_path / "docs" / "contracts"
    empty_contracts_dir.mkdir(parents=True)
    load_thresholds.cache_clear()
    monkeypatch.setattr(contracts, "contracts_directory", lambda: empty_contracts_dir)

    with pytest.raises(ContractsNotFoundError):
        load_thresholds()

    load_thresholds.cache_clear()
