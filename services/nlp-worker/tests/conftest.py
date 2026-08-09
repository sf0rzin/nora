"""Global fixtures.

`get_settings` uses `@lru_cache` to avoid re-parsing `.env` on every
`Depends(get_settings)`. In tests, that means mutating env vars after the
first read has no effect until `cache_clear()`. Clearing before AND after
each test guarantees isolation.
"""

from __future__ import annotations

import pytest

from nora_nlp.settings import get_settings


@pytest.fixture(autouse=True)
def _reset_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
