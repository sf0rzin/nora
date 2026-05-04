"""Configuracoes do worker carregadas via variaveis de ambiente."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Settings centralizados. Sobrescritos por env ou .env.local."""

    model_config = SettingsConfigDict(
        env_file=(".env", ".env.local"),
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    worker_port: int = Field(default=8001, alias="WORKER_PORT")
    log_level: str = Field(default="info", alias="LOG_LEVEL")

    llm_provider: str = Field(default="openrouter", alias="LLM_PROVIDER")
    openrouter_api_key: str = Field(default="", alias="OPENROUTER_API_KEY")
    openrouter_model: str = Field(default="openai/gpt-4o", alias="OPENROUTER_MODEL")
    openrouter_base_url: str = Field(
        default="https://openrouter.ai/api/v1", alias="OPENROUTER_BASE_URL"
    )

    use_llm_stub: bool = Field(default=True, alias="USE_LLM_STUB")


def get_settings() -> Settings:
    """Factory para uso em dependency injection do FastAPI."""
    return Settings()
