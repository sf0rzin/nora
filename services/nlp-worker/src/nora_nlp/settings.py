"""Configurações do worker carregadas via variáveis de ambiente.

Provider de LLM é agnóstico (ADR 0004): qualquer endpoint compatível com a API
Chat Completions da OpenAI funciona apontando ``LLM_BASE_URL``. Default no MVP
é OpenAI direto com ``gpt-4o-mini``.
"""

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

    # LLM provider-agnostic (ADR 0004). Default: OpenAI direto / gpt-4o-mini.
    llm_provider: str = Field(default="openai", alias="LLM_PROVIDER")
    llm_base_url: str = Field(default="https://api.openai.com/v1", alias="LLM_BASE_URL")
    llm_api_key: str = Field(default="", alias="LLM_API_KEY")
    llm_model: str = Field(default="gpt-4o-mini", alias="LLM_MODEL")
    llm_temperature: float = Field(default=0.2, alias="LLM_TEMPERATURE")

    use_llm_stub: bool = Field(default=True, alias="USE_LLM_STUB")


def get_settings() -> Settings:
    """Factory para uso em dependency injection do FastAPI."""
    return Settings()
