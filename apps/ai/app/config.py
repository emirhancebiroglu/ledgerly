from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_")

    service_name: str = "ai"
    service_version: str = "0.1.0"
    enable_docs: bool = False
    cors_origins: list[str] = ["http://localhost:3000"]

    # Mirrors api's own upload cap. Enforced here too: `ai` must not assume its only caller
    # validated anything, even though today it has exactly one.
    max_document_bytes: int = 10 * 1024 * 1024

    # Which LlmClient adapter to use. "litellm" is the real M5 adapter; "fake" keeps the M4 stub
    # available for tests and offline runs.
    llm_provider: str = "litellm"

    # LiteLLM model string. Decided at M5 planning: Gemini 3.6 Flash, GA and native-PDF vision.
    llm_model: str = "gemini/gemini-3.6-flash"

    # Read directly rather than left to LiteLLM's own env lookup, so a missing key fails at
    # startup with a message naming this service, not on the first request.
    llm_api_key: str | None = None

    llm_timeout_seconds: float = 30.0
    llm_max_retries: int = 2
    llm_circuit_breaker_failure_threshold: int = 5
    llm_circuit_breaker_cooldown_seconds: float = 30.0


settings = Settings()

if settings.llm_provider == "litellm" and not settings.llm_api_key:
    raise RuntimeError(
        "AI_LLM_API_KEY is required when AI_LLM_PROVIDER=litellm. Set it before starting the "
        "service, or set AI_LLM_PROVIDER=fake for a stub run."
    )
