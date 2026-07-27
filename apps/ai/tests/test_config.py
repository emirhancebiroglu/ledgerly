"""T1 — a missing API key must fail at startup, not on the first request.

``app.config`` raises at import time, so this has to run in a subprocess: importing it once
already happened for every other test in this process via ``conftest.py``.
"""

import subprocess
import sys
from pathlib import Path

AI_APP_ROOT = Path(__file__).resolve().parents[1]


def run_import(env: dict) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-c", "import app.config"],
        cwd=AI_APP_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )


def test_litellm_provider_without_an_api_key_fails_at_import(monkeypatch):
    import os

    env = dict(os.environ)
    env["AI_LLM_PROVIDER"] = "litellm"
    env.pop("AI_LLM_API_KEY", None)

    result = run_import(env)

    assert result.returncode != 0
    assert "AI_LLM_API_KEY" in result.stderr


def test_litellm_provider_with_an_api_key_imports_cleanly():
    import os

    env = dict(os.environ)
    env["AI_LLM_PROVIDER"] = "litellm"
    env["AI_LLM_API_KEY"] = "test-key"

    result = run_import(env)

    assert result.returncode == 0, result.stderr


def test_fake_provider_never_requires_an_api_key():
    import os

    env = dict(os.environ)
    env["AI_LLM_PROVIDER"] = "fake"
    env.pop("AI_LLM_API_KEY", None)

    result = run_import(env)

    assert result.returncode == 0, result.stderr
