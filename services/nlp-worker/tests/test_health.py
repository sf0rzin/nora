from fastapi.testclient import TestClient

from nora_nlp.main import app

client = TestClient(app)


def test_healthz_ok():
    resp = client.get("/healthz")
    assert resp.status_code == 200
    body = resp.json()
    assert body["service"] == "nora-nlp-worker"
    assert body["status"] == "ok"
