"""Tests for the nlp-baseline x worker integration.

Covers:
* ``extract_baseline_terms`` returns a populated list for a real PT-BR transcript
* score is always > 0
* score is always <= 1 (see the ``BaselineTerm.score`` constraint)
* the /analyze pipeline (stub mode) returns ``baselineTerms`` populated
* empty list for degenerate input (empty / very short transcript)
* does not break the existing contract (optional field, empty default)
"""

from __future__ import annotations

import json
import os
import pathlib

from fastapi.testclient import TestClient

os.environ["USE_LLM_STUB"] = "true"

from nora_nlp.main import app
from nora_nlp.models import BaselineTerm
from nora_nlp.services.baseline import extract_baseline_terms

client = TestClient(app)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DATA_DIR = REPO_ROOT / "data" / "synthetic"


def _read_meeting(name: str) -> str:
    return (DATA_DIR / "meetings" / name).read_text(encoding="utf-8")


# ---------- Unit tests for extract_baseline_terms ----------


def test_extract_returns_list_of_baseline_term_for_real_transcript() -> None:
    transcript = _read_meeting("01-acme-discovery-lead-novo.txt")
    result = extract_baseline_terms(transcript, top_n=10)
    assert result, "Expected a non-empty list for a real PT-BR transcript."
    for item in result:
        assert isinstance(item, BaselineTerm)


def test_extract_terms_have_positive_score() -> None:
    transcript = _read_meeting("01-acme-discovery-lead-novo.txt")
    result = extract_baseline_terms(transcript, top_n=10)
    assert result
    for item in result:
        assert item.score > 0.0, f"Score should be > 0, got {item.score} for '{item.term}'"


def test_extract_scores_are_in_valid_range() -> None:
    """Schema constraint: score in [0, 1]."""
    transcript = _read_meeting("05-northwind-renewal-churn-risco.txt")
    result = extract_baseline_terms(transcript, top_n=20)
    assert result
    for item in result:
        assert 0.0 <= item.score <= 1.0


def test_extract_respects_top_n_limit() -> None:
    transcript = _read_meeting("01-acme-discovery-lead-novo.txt")
    result = extract_baseline_terms(transcript, top_n=5)
    assert len(result) <= 5


def test_extract_returns_terms_sorted_descending() -> None:
    transcript = _read_meeting("01-acme-discovery-lead-novo.txt")
    result = extract_baseline_terms(transcript, top_n=10)
    scores = [t.score for t in result]
    assert scores == sorted(scores, reverse=True)


def test_extract_handles_empty_transcript() -> None:
    assert extract_baseline_terms("", top_n=10) == []
    assert extract_baseline_terms("   \n\t  ", top_n=10) == []


def test_extract_handles_top_n_zero() -> None:
    transcript = _read_meeting("01-acme-discovery-lead-novo.txt")
    assert extract_baseline_terms(transcript, top_n=0) == []


def test_extract_does_not_raise_on_stopwords_only_input() -> None:
    # Text with stopwords only --- no useful vocabulary forms. We expect an empty
    # list (not an exception).
    only_stopwords = "o a de e que para com no em do da pelo"
    assert extract_baseline_terms(only_stopwords, top_n=10) == []


def test_extract_short_text_does_not_crash() -> None:
    """Degenerate case: very short text. Must not raise an exception."""
    result = extract_baseline_terms("proposta comercial", top_n=10)
    # Even short, it should manage to extract something (1 chunk).
    # But if it cannot, at least it returns an empty list.
    assert isinstance(result, list)
    for item in result:
        assert 0.0 <= item.score <= 1.0


def test_extract_redacted_placeholders_dont_dominate() -> None:
    """Sanity: placeholders [[EMAIL_1]] etc do not dominate the ranking.

    After PII redaction the transcript has tokens like `[[EMAIL_1]]`. Since
    normalize strips punctuation, they become `email` --- but TF-IDF must keep
    capturing the business terms. We test that at least some terms are
    *not* placeholders.
    """
    transcript = (
        "[[EMAIL_1]] Marina nos enviou a proposta de renovacao. "
        "Vamos avaliar o contrato anual antes de fechar. "
        "Carlos sugeriu desconto. [[EMAIL_2]] "
        "Renovacao ficou aprovada pela diretoria com novo valor mensal."
    )
    result = extract_baseline_terms(transcript, top_n=10)
    terms = {t.term for t in result}
    # At least one business term shows up (and not just a reduced placeholder)
    business = {"proposta", "renovacao", "contrato", "anual", "desconto", "valor", "mensal"}
    assert terms & business, f"Expected business terms in {terms}"


# ---------- E2E /analyze ----------


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


def test_analyze_endpoint_returns_baseline_terms() -> None:
    payload = _load_request(
        "01-acme-discovery-lead-novo.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Field present (even if empty in degenerate cases)
    assert "baselineTerms" in body, f"available keys: {list(body.keys())}"
    terms = body["baselineTerms"]
    assert isinstance(terms, list)
    assert terms, "Expected a non-empty list for a real transcript."

    for entry in terms:
        assert set(entry.keys()) == {"term", "score"}
        assert isinstance(entry["term"], str)
        assert isinstance(entry["score"], float | int)
        assert 0.0 <= entry["score"] <= 1.0


def test_analyze_endpoint_does_not_break_existing_contract() -> None:
    """The response still has all the old fields --- baselineTerms is additive."""
    payload = _load_request(
        "05-northwind-renewal-churn-risco.txt",
        "northwind-fintech",
        "00000000-0000-4000-8000-000000000bbb",
    )
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Pre-existing fields kept
    for key in (
        "summary",
        "decisions",
        "actionItems",
        "risks",
        "opportunities",
        "sentimentOverall",
        "topics",
        "participants",
        "metadata",
    ):
        assert key in body, f"pre-existing field {key!r} disappeared from the response"

    # New field present
    assert "baselineTerms" in body
