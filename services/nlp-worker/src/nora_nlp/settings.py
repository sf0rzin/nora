"""Worker configuration loaded via environment variables.

The LLM provider is agnostic (ADR 0004): any endpoint compatible with OpenAI's
Chat Completions API works by pointing ``LLM_BASE_URL``. Default in the MVP
is OpenAI direct with ``gpt-4o-mini``.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Centralized settings. Overridden by env or .env.local."""

    model_config = SettingsConfigDict(
        env_file=(".env", ".env.local"),
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
        # populate_by_name=True allows instantiating Settings(llm_provider="x")
        # besides Settings(LLM_PROVIDER="x"). Without it, lower_snake_case kwargs
        # were silently ignored (the defaults coincided by luck).
        populate_by_name=True,
    )

    worker_port: int = Field(default=8001, alias="WORKER_PORT")
    log_level: str = Field(default="info", alias="LOG_LEVEL")

    # LLM provider-agnostic (ADR 0004). Default: OpenAI direct / gpt-4o-mini.
    llm_provider: str = Field(default="openai", alias="LLM_PROVIDER")
    llm_base_url: str = Field(default="https://api.openai.com/v1", alias="LLM_BASE_URL")
    llm_api_key: str = Field(default="", alias="LLM_API_KEY")
    llm_model: str = Field(default="gpt-4o-mini", alias="LLM_MODEL")
    llm_temperature: float = Field(default=0.2, alias="LLM_TEMPERATURE")

    use_llm_stub: bool = Field(default=True, alias="USE_LLM_STUB")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached factory for FastAPI dependency injection.

    `@lru_cache` avoids re-parsing the .env on every request (without cache, it was I/O
    on every `Depends(get_settings)`). In tests, call `get_settings.cache_clear()`
    after mutating env vars.
    """
    return Settings()
