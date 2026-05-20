from fastapi.testclient import TestClient

from nora_nlp.main import app
from nora_nlp.settings import Settings, get_settings

client = TestClient(app)


def test_healthz_ok():
    resp = client.get("/healthz")
    assert resp.status_code == 200
    body = resp.json()
    assert body["service"] == "nora-nlp-worker"
    assert body["status"] == "ok"


def test_readyz_ok_when_stub_mode_even_without_api_key():
    app.dependency_overrides[get_settings] = lambda: Settings(
        use_llm_stub=True,
        llm_api_key="",
    )
    try:
        resp = client.get("/readyz")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ready"
        assert body["stubMode"] == "true"
    finally:
        app.dependency_overrides.pop(get_settings, None)


def test_readyz_503_when_real_llm_without_api_key():
    app.dependency_overrides[get_settings] = lambda: Settings(
        use_llm_stub=False,
        llm_api_key="",
    )
    try:
        resp = client.get("/readyz")
        assert resp.status_code == 503
        body = resp.json()
        assert body["detail"]["code"] == "NOT_READY"
    finally:
        app.dependency_overrides.pop(get_settings, None)


def test_readyz_ok_when_real_llm_with_api_key():
    app.dependency_overrides[get_settings] = lambda: Settings(
        use_llm_stub=False,
        llm_api_key="sk-test-fake",
    )
    try:
        resp = client.get("/readyz")
        assert resp.status_code == 200
        body = resp.json()
        assert body["stubMode"] == "false"
    finally:
        app.dependency_overrides.pop(get_settings, None)
