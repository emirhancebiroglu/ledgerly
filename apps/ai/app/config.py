from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_")

    service_name: str = "ai"
    service_version: str = "0.1.0"
    enable_docs: bool = False


settings = Settings()
