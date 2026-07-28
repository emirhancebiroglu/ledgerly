from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_")

    service_name: str = "ai"
    service_version: str = "0.1.0"
    enable_docs: bool = False
    cors_origins: list[str] = ["http://localhost:3000"]
    service_token: SecretStr
    rate_limit_enabled: bool = True
    rate_limit_redis_url: str = "redis://localhost:6379/0"
    rate_limit_max_requests: int = Field(default=30, gt=0)
    rate_limit_window_seconds: int = Field(default=60, gt=0)

    @field_validator("service_token")
    @classmethod
    def service_token_must_not_be_blank(cls, value: SecretStr) -> SecretStr:
        if not value.get_secret_value().strip():
            raise ValueError("AI_SERVICE_TOKEN must not be blank")
        return value

    # Mirrors api's own upload cap. Enforced here too: `ai` must not assume its only caller
    # validated anything, even though today it has exactly one.
    max_document_bytes: int = 10 * 1024 * 1024

    # Which LlmClient adapter to use. "litellm" is the real M5 adapter; "fake" keeps the M4 stub
    # available for tests and offline runs.
    llm_provider: str = "litellm"

    # LiteLLM model string. Gemini (the original M5 Q1 decision) was superseded before the gate
    # ever ran: its free tier is a hard 20-requests/day account-wide ceiling, confirmed not
    # preview-model-specific — nowhere near enough for even one eval run. qwen3.7-plus via
    # OpenCode Go's Anthropic-compatible gateway is the actual deployed default — see decisions.md.
    llm_model: str = "anthropic/qwen3.7-plus"

    # Read directly rather than left to LiteLLM's own env lookup, so a missing key fails at
    # startup with a message naming this service, not on the first request.
    llm_api_key: str | None = None

    # 30s was too tight in practice — the M5 gate run against a real vision model saw p95 latency
    # of ~90s per call. A false timeout is indistinguishable from a real one downstream (both
    # retry, both can open the circuit breaker), so the timeout has to clear real p95, not p50.
    llm_timeout_seconds: float = 120.0
    llm_max_retries: int = 2
    llm_circuit_breaker_failure_threshold: int = 5
    llm_circuit_breaker_cooldown_seconds: float = 30.0

    # OpenCode Go's Anthropic-compatible gateway. Set to None to fall back to LiteLLM's normal
    # provider-prefix routing (e.g. for gemini/... or any other native-routed model).
    llm_api_base: str | None = "https://opencode.ai/zen/go"

    # Only Gemini's native API accepts a PDF as a `file` content block through LiteLLM; every
    # other gateway tried (OpenCode Go, every OpenRouter free vision model) rejects it. Non-native-
    # PDF providers render PDF pages to PNG first — see app.llm.pdf_to_images.
    llm_supports_native_pdf: bool = False

    # Which EmbeddingClient adapter to use. "litellm" is the real M6 adapter; "fake" keeps a
    # deterministic stub available for tests and offline runs, same split as llm_provider above.
    embedding_provider: str = "litellm"
    embedding_model: str = "anthropic/qwen3.7-plus"
    embedding_api_key: str | None = None
    embedding_dimensions: int = 1536
    embedding_timeout_seconds: float = 30.0
    embedding_api_base: str | None = "https://opencode.ai/zen/go"

    anomaly_medium_z_score: float = Field(default=2.0, gt=0)
    anomaly_high_z_score: float = Field(default=3.0, gt=0)
    anomaly_medium_burn_rate: float = Field(default=0.80, ge=0)
    anomaly_high_burn_rate: float = Field(default=1.0, ge=0)


settings = Settings()

if settings.llm_provider == "litellm" and not settings.llm_api_key:
    raise RuntimeError(
        "AI_LLM_API_KEY is required when AI_LLM_PROVIDER=litellm. Set it before starting the "
        "service, or set AI_LLM_PROVIDER=fake for a stub run."
    )

if settings.embedding_provider == "litellm" and not settings.embedding_api_key:
    raise RuntimeError(
        "AI_EMBEDDING_API_KEY is required when AI_EMBEDDING_PROVIDER=litellm. Set it before "
        "starting the service, or set AI_EMBEDDING_PROVIDER=fake for a stub run."
    )
