"""Fixtures globais.

`get_settings` usa `@lru_cache` para evitar re-parsing de `.env` a cada
`Depends(get_settings)`. Em testes, isso significa que mutar env vars apos a
primeira leitura nao surte efeito ate `cache_clear()`. Limpar antes E depois de
cada teste garante isolamento.
"""

from __future__ import annotations

import pytest

from nora_nlp.settings import get_settings


@pytest.fixture(autouse=True)
def _reset_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
