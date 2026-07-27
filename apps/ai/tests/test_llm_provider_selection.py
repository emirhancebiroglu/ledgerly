"""``AI_LLM_PROVIDER`` picks the adapter `main.get_llm_client` builds — the config decision T1/T2
promised: a provider swap is configuration, not code."""

from __future__ import annotations

from app.llm import FakeLlmClient, LiteLlmClient, ResilientLlmClient
from app.main import get_llm_client


def test_fake_provider_resolves_to_the_stub(monkeypatch):
    from app import config as config_module

    monkeypatch.setattr(config_module.settings, "llm_provider", "fake")

    assert isinstance(get_llm_client(), FakeLlmClient)


def test_litellm_provider_resolves_to_a_resilient_real_adapter(monkeypatch):
    from app import config as config_module

    monkeypatch.setattr(config_module.settings, "llm_provider", "litellm")
    monkeypatch.setattr(config_module.settings, "llm_api_key", "test-key")
    monkeypatch.setattr(config_module.settings, "llm_model", "gemini/gemini-3.6-flash")

    client = get_llm_client()

    assert isinstance(client, ResilientLlmClient)
    assert isinstance(client._inner, LiteLlmClient)
    assert client.model_name == "gemini/gemini-3.6-flash"


def test_an_unknown_provider_raises_at_resolution(monkeypatch):
    import pytest

    from app import config as config_module

    monkeypatch.setattr(config_module.settings, "llm_provider", "not-a-real-provider")

    with pytest.raises(RuntimeError):
        get_llm_client()
