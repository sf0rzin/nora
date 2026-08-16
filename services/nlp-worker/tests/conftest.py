"""Global fixtures.

`get_settings` uses `@lru_cache` to avoid re-parsing `.env` on every
`Depends(get_settings)`. In tests, that means mutating env vars after the
first read has no effect until `cache_clear()`. Clearing before AND after
each test guarantees isolation.
"""

from __future__ import annotations

import pytest

from nora_nlp.main import app
from nora_nlp.security import require_internal_token
from nora_nlp.settings import get_settings


@pytest.fixture(autouse=True)
def _reset_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture(autouse=True)
def _bypass_internal_auth():
    """Neutralize the `X-Internal-Token` gate for every suite except its own.

    The analysis router is fail-closed: with no token configured it answers 503, which is
    the whole point of `security.py`. Every pre-existing test posts to `/analyze`,
    `/analyze-live` or `/split` without a header, so without this the entire suite would go
    red on a change that is about who may call the worker, not about what it computes.

    `tests/test_internal_auth.py` removes the override with `_enforce_internal_auth`, so the
    gate itself is still exercised for real.
    """
    app.dependency_overrides[require_internal_token] = lambda: None
    yield
    app.dependency_overrides.pop(require_internal_token, None)
