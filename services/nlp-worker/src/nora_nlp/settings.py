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

    llm_provider: str = Field(default="azure_openai", alias="LLM_PROVIDER")
    azure_openai_endpoint: str = Field(default="", alias="AZURE_OPENAI_ENDPOINT")
    azure_openai_api_key: str = Field(default="", alias="AZURE_OPENAI_API_KEY")
    azure_openai_deployment_chat: str = Field(
        default="gpt-4o", alias="AZURE_OPENAI_DEPLOYMENT_CHAT"
    )
    azure_openai_deployment_embedding: str = Field(
        default="text-embedding-3-large", alias="AZURE_OPENAI_DEPLOYMENT_EMBEDDING"
    )
    azure_openai_api_version: str = Field(
        default="2024-08-01-preview", alias="AZURE_OPENAI_API_VERSION"
    )

    use_llm_stub: bool = Field(default=True, alias="USE_LLM_STUB")


def get_settings() -> Settings:
    """Factory para uso em dependency injection do FastAPI."""
    return Settings()
