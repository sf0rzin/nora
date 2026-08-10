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

    # Minimum schema structure
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
        "03-acme-upsell-manufatura-concorrente.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    resp = client.post("/analyze", json=payload)
    body = resp.json()
    categories = {r["category"] for r in body["risks"]}
    # The meeting mentions a competitor; we expect at least 1 identified risk.
    assert body["risks"], "Expected at least 1 risk identified."
    assert categories & {"COMPETITION", "PRICE"}


# ---------- Productivity Score (ADR 0005) ----------


def test_productivity_is_null_without_goal():
    """Without a declared goal, productivity comes back null (ADR 0005 — opt-in)."""
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    resp = client.post("/analyze", json=payload)
    body = resp.json()
    assert body["productivity"] is None


def test_productivity_present_when_goal_declared():
    """With a declared goal, productivity comes back populated with the full schema."""
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    payload["goal"] = {
        "purpose": "Discovery com lead pra entender dores",
        "expectedOutcomes": [
            "Identificar tomador de decisao",
            "Mapear timeline da implantacao",
        ],
    }
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    prod = body["productivity"]
    assert prod is not None
    assert 0 <= prod["score"] <= 100
    assert prod["band"] in {"LOW", "MEDIUM", "HIGH"}
    assert len(prod["coverage"]) == len(payload["goal"]["expectedOutcomes"])
    for c in prod["coverage"]:
        assert c["status"] in {"ADDRESSED", "PARTIAL", "MISSED"}
        assert "expectedOutcome" in c
    assert isinstance(prod["rationale"], str) and len(prod["rationale"]) >= 10


def test_productivity_band_threshold_consistency():
    """Band derived from the score must respect the ADR limits (LOW<40, MEDIUM 40-69, HIGH>=70)."""
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    # Outcomes intentionally unrelated to force MISSED → low score
    payload["goal"] = {
        "purpose": "Reuniao sobre topicos nao mencionados",
        "expectedOutcomes": [
            "Discutir migracao do mainframe COBOL pro Kubernetes",
            "Decidir cores da nova landing page institucional",
            "Aprovar contrato de paisagismo do escritorio",
        ],
    }
    resp = client.post("/analyze", json=payload)
    body = resp.json()
    prod = body["productivity"]
    assert prod is not None
    score = prod["score"]
    band = prod["band"]
    if score < 40:
        assert band == "LOW"
    elif score < 70:
        assert band == "MEDIUM"
    else:
        assert band == "HIGH"


# ---------- Customer Confidence (ADR 0006) ----------

_BUYING_SIGNAL_TYPES = {
    "BUDGET_DISCUSSED",
    "TIMELINE_DISCUSSED",
    "STAKEHOLDER_INVOLVED",
    "NEXT_STEP_REQUESTED",
    "REFERENCE_REQUESTED",
    "PROPOSAL_REQUESTED",
    "OTHER",
}
_OBJECTION_TYPES = {
    "PRICE",
    "TIMELINE",
    "AUTHORITY",
    "NEED",
    "COMPETITOR_MENTION",
    "TRUST",
    "FEATURE_GAP",
    "OTHER",
}


@pytest.mark.parametrize(
    "transcript,tenant",
    [
        ("01-acme-discovery-lead-novo.txt", "acme-software"),
        ("06-northwind-prospect-poc.txt", "northwind-fintech"),
        ("08-northwind-objecao-integracao-tecnica.txt", "northwind-fintech"),
    ],
)
def test_customer_confidence_present_for_sales_conversation(transcript: str, tenant: str):
    """Client/lead conversations emit customerConfidence with the full schema (ADR 0006)."""
    payload = _load_request(transcript, tenant, "00000000-0000-4000-8000-000000000aaa")
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    cc = resp.json()["customerConfidence"]

    assert cc is not None, "Expected customerConfidence to be emitted for a sales conversation."
    assert 0 <= cc["score"] <= 100
    assert cc["band"] in {"LOW", "MEDIUM", "HIGH"}
    assert cc["trend"] in {"IMPROVING", "STABLE", "DECLINING", None}
    # accountName is nullable (LLM detects it; stub does not parse the name -> null).
    assert cc["accountName"] is None or isinstance(cc["accountName"], str)
    assert isinstance(cc["rationale"], str) and len(cc["rationale"]) >= 10
    # Band derived from the score respects the ADR limits (LOW<40, MEDIUM 40-69, HIGH>=70).
    score, band = cc["score"], cc["band"]
    if score < 40:
        assert band == "LOW"
    elif score < 70:
        assert band == "MEDIUM"
    else:
        assert band == "HIGH"
    # Every buyingSignal/objection needs a quote + valid enum (prompt rule).
    assert isinstance(cc["buyingSignals"], list)
    for sig in cc["buyingSignals"]:
        assert sig["type"] in _BUYING_SIGNAL_TYPES
        assert isinstance(sig["quote"], str) and len(sig["quote"]) >= 5
    assert isinstance(cc["objections"], list)
    for obj in cc["objections"]:
        assert obj["type"] in _OBJECTION_TYPES
        assert isinstance(obj["quote"], str) and len(obj["quote"]) >= 5
        assert obj["severity"] in {"LOW", "MEDIUM", "HIGH"}


def test_customer_confidence_null_for_internal_meeting():
    """Internal meeting with no sales cues emits customerConfidence = null (ADR 0006).

    Gating decided by content (LLM in real mode; cue-based in the stub): with no
    buying signal nor objection, the field comes back null — proving the nullable contract.
    """
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    # Replace the transcript with a cue-free internal alignment (team daily).
    payload["transcript"] = (
        "[Ana] Bom dia time, vamos revisar o andamento das tarefas de hoje.\n"
        "[Bruno] Terminei o ajuste no layout da tela inicial ontem a noite.\n"
        "[Ana] Otimo. Alguem travado em algo?\n"
        "[Bruno] Nada por aqui, fluindo bem.\n"
        "[Ana] Entao seguimos. Bom trabalho a todos."
    )
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    assert resp.json()["customerConfidence"] is None
