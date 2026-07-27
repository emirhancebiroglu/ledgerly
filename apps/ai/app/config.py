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

    # Which LlmClient adapter to use. Only "fake" exists at M4; the provider decision is deferred
    # to M5, where the eval harness can measure candidates instead of guessing.
    llm_provider: str = "fake"


settings = Settings()
