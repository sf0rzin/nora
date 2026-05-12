import json
import os
import pathlib

import pytest
from fastapi.testclient import TestClient

os.environ["USE_LLM_STUB"] = "true"

from nora_nlp.main import app

client = TestClient(app)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DATA_DIR = REPO_ROOT / "data" / "synthetic"


def _load_request(transcript_file: str, tenant_slug: str, meeting_id: str) -> dict:
    transcript = (DATA_DIR / "meetings" / transcript_file).read_text(encoding="utf-8")
    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / f"{tenant_slug}.context.json").read_text(encoding="utf-8")
    )
    return {
        "meetingId": meeting_id,
        "tenantId": "00000000-0000-4000-8000-000000000001",
        "language": "pt-BR",
        "transcript": transcript,
        "tenantContext": tenant_ctx,
        "options": {
            "includeRisks": True,
            "includeOpportunities": True,
            "maxActionItems": 20,
            "promptVersion": "meeting-analysis-v1",
        },
    }


@pytest.mark.parametrize(
    "transcript,tenant",
    [
        ("01-acme-discovery-lead-novo.txt", "acme-software"),
        ("05-northwind-renewal-churn-risco.txt", "northwind-fintech"),
        ("11-solo-roadmap-concorrente.txt", "solo-launch"),
    ],
)
def test_analyze_returns_valid_response(transcript: str, tenant: str):
    payload = _load_request(transcript, tenant, "00000000-0000-4000-8000-000000000aaa")
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Estrutura minima do schema
    assert body["meetingId"] == payload["meetingId"]
    assert isinstance(body["summary"], str) and len(body["summary"]) >= 30
    assert "decisions" in body
    assert "actionItems" in body
    assert "risks" in body
    assert "opportunities" in body
    assert body["sentimentOverall"] in {"POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED"}
    assert isinstance(body["topics"], list)
    assert body["metadata"]["modelVersion"].startswith("stub")


def test_analyze_acme_detects_competition_risk():
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt", "acme-software", "00000000-0000-4000-8000-000000000aaa"
    )
    resp = client.post("/analyze", json=payload)
    body = resp.json()
    categories = {r["category"] for r in body["risks"]}
    # A reuniao menciona TotalSys e proposta abaixo; esperamos pelo menos 1 risco
    assert body["risks"], "Esperava ao menos 1 risco identificado."
    assert categories & {"COMPETITION", "PRICE"}
