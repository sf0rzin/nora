"""The `X-Internal-Token` gate on the analysis routes.

`tests/conftest.py` neutralizes the gate for every other suite. This module opts back into
the real dependency (`_enforce_internal_auth`) so the three states are exercised: token
configured and matching, token configured and not matching, token not configured at all.

The request bodies here are intentionally minimal and sometimes invalid: authentication runs
as a router dependency, i.e. BEFORE the body is validated, so a 401/503 must win over a 422.
That ordering is the property under test — a gate that only rejects well-formed payloads is
not a gate.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from nora_nlp.main import app
from nora_nlp.security import require_internal_token
from nora_nlp.settings import Settings, get_settings

client = TestClient(app)

TOKEN = "s3cr3t-internal-token-for-tests"


@pytest.fixture
def _enforce_internal_auth():
    """Undo conftest's blanket override so the real dependency runs."""
    app.dependency_overrides.pop(require_internal_token, None)
    yield


def _use_settings(**kwargs) -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(**kwargs)


@pytest.fixture(autouse=True)
def _clear_settings_override():
    yield
    app.dependency_overrides.pop(get_settings, None)


def test_analyze_401_without_header_when_token_configured(_enforce_internal_auth):
    _use_settings(worker_internal_token=TOKEN)
    resp = client.post("/analyze", json={})
    assert resp.status_code == 401, resp.text
    assert resp.json()["detail"]["code"] == "UNAUTHORIZED"


def test_analyze_401_with_wrong_token(_enforce_internal_auth):
    _use_settings(worker_internal_token=TOKEN)
    resp = client.post("/analyze", json={}, headers={"X-Internal-Token": "wrong"})
    assert resp.status_code == 401, resp.text
    assert resp.json()["detail"]["code"] == "UNAUTHORIZED"


def test_analyze_passes_auth_with_correct_token(_enforce_internal_auth):
    """The gate lets the request through: it now fails on the BODY (422), not on auth."""
    _use_settings(worker_internal_token=TOKEN, use_llm_stub=True)
    resp = client.post("/analyze", json={}, headers={"X-Internal-Token": TOKEN})
    assert resp.status_code == 422, resp.text


def test_analyze_503_when_token_not_configured(_enforce_internal_auth):
    _use_settings(worker_internal_token="", allow_unauthenticated_internal=False)
    resp = client.post("/analyze", json={})
    assert resp.status_code == 503, resp.text
    detail = resp.json()["detail"]
    assert detail["code"] == "INTERNAL_AUTH_NOT_CONFIGURED"
    assert "NORA_WORKER_INTERNAL_TOKEN" in detail["reason"]


def test_analyze_open_when_opt_out_enabled(_enforce_internal_auth):
    """Explicit local-dev opt-out: no token, no header, and the gate steps aside."""
    _use_settings(
        worker_internal_token="", allow_unauthenticated_internal=True, use_llm_stub=True
    )
    resp = client.post("/analyze", json={}, headers={})
    assert resp.status_code == 422, resp.text


@pytest.mark.parametrize("path", ["/analyze", "/analyze-live", "/split"])
def test_every_analysis_route_is_gated(_enforce_internal_auth, path: str):
    """The dependency is on the router, so a route added later inherits it."""
    _use_settings(worker_internal_token=TOKEN)
    assert client.post(path, json={}).status_code == 401


def test_healthz_stays_open_without_header(_enforce_internal_auth):
    """The compose healthcheck calls this with no header; gating it kills the stack."""
    _use_settings(worker_internal_token=TOKEN)
    assert client.get("/healthz").status_code == 200


def test_readyz_reports_internal_auth_on(_enforce_internal_auth):
    _use_settings(worker_internal_token=TOKEN, use_llm_stub=True)
    resp = client.get("/readyz")
    assert resp.status_code == 200, resp.text
    assert resp.json()["internalAuth"] == "on"


def test_readyz_reports_internal_auth_off(_enforce_internal_auth):
    _use_settings(worker_internal_token="", use_llm_stub=True)
    resp = client.get("/readyz")
    assert resp.status_code == 200, resp.text
    assert resp.json()["internalAuth"] == "off"
