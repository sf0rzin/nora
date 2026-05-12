"""Testes da integracao nlp-baseline x worker.

Cobre:
* ``extract_baseline_terms`` retorna lista popular para transcricao PT-BR real
* score eh sempre > 0
* score eh sempre <= 1 (vide ``BaselineTerm.score`` constraint)
* pipeline /analyze (stub mode) retorna ``baselineTerms`` populado
* lista vazia para input degenerado (transcript vazio / muito curto)
* nao quebra contrato existente (campo opcional, default vazio)
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


# ---------- Unitarios de extract_baseline_terms ----------


def test_extract_returns_list_of_baseline_term_for_real_transcript() -> None:
    transcript = (DATA_DIR / "meetings" / "01-acme-discovery.txt").read_text(encoding="utf-8")
    result = extract_baseline_terms(transcript, top_n=10)
    assert result, "Esperava lista nao-vazia para transcript PT-BR real."
    for item in result:
        assert isinstance(item, BaselineTerm)


def test_extract_terms_have_positive_score() -> None:
    transcript = (DATA_DIR / "meetings" / "01-acme-discovery.txt").read_text(encoding="utf-8")
    result = extract_baseline_terms(transcript, top_n=10)
    assert result
    for item in result:
        assert item.score > 0.0, f"Score deveria ser > 0, achei {item.score} em '{item.term}'"


def test_extract_scores_are_in_valid_range() -> None:
    """Schema constraint: score em [0, 1]."""
    transcript = (DATA_DIR / "meetings" / "03-northwind-renewal.txt").read_text(encoding="utf-8")
    result = extract_baseline_terms(transcript, top_n=20)
    assert result
    for item in result:
        assert 0.0 <= item.score <= 1.0


def test_extract_respects_top_n_limit() -> None:
    transcript = (DATA_DIR / "meetings" / "01-acme-discovery.txt").read_text(encoding="utf-8")
    result = extract_baseline_terms(transcript, top_n=5)
    assert len(result) <= 5


def test_extract_returns_terms_sorted_descending() -> None:
    transcript = (DATA_DIR / "meetings" / "01-acme-discovery.txt").read_text(encoding="utf-8")
    result = extract_baseline_terms(transcript, top_n=10)
    scores = [t.score for t in result]
    assert scores == sorted(scores, reverse=True)


def test_extract_handles_empty_transcript() -> None:
    assert extract_baseline_terms("", top_n=10) == []
    assert extract_baseline_terms("   \n\t  ", top_n=10) == []


def test_extract_handles_top_n_zero() -> None:
    transcript = (DATA_DIR / "meetings" / "01-acme-discovery.txt").read_text(encoding="utf-8")
    assert extract_baseline_terms(transcript, top_n=0) == []


def test_extract_does_not_raise_on_stopwords_only_input() -> None:
    # Texto so com stopwords --- vocabulario util nao se forma. Esperamos lista
    # vazia (nao excecao).
    only_stopwords = "o a de e que para com no em do da pelo"
    assert extract_baseline_terms(only_stopwords, top_n=10) == []


def test_extract_short_text_does_not_crash() -> None:
    """Caso degenerado: texto muito curto. Nao pode levantar excecao."""
    result = extract_baseline_terms("proposta comercial", top_n=10)
    # Mesmo curto, deveria conseguir extrair algo (1 chunk).
    # Mas se nao conseguir, ao menos retorna lista vazia.
    assert isinstance(result, list)
    for item in result:
        assert 0.0 <= item.score <= 1.0


def test_extract_redacted_placeholders_dont_dominate() -> None:
    """Sanidade: placeholders [[EMAIL_1]] etc nao dominam o ranking.

    Apos PII redaction o transcript tem tokens tipo `[[EMAIL_1]]`. Como o
    normalize remove pontuacao, viram `email` --- mas o TF-IDF deve continuar
    capturando os termos de negocio. Testamos que pelo menos alguns termos
    *nao* sao placeholders.
    """
    transcript = (
        "[[EMAIL_1]] Marina nos enviou a proposta de renovacao. "
        "Vamos avaliar o contrato anual antes de fechar. "
        "Carlos sugeriu desconto. [[EMAIL_2]] "
        "Renovacao ficou aprovada pela diretoria com novo valor mensal."
    )
    result = extract_baseline_terms(transcript, top_n=10)
    terms = {t.term for t in result}
    # Pelo menos um termo de negocio aparece (e nao soh placeholder reduzido)
    business = {"proposta", "renovacao", "contrato", "anual", "desconto", "valor", "mensal"}
    assert terms & business, f"Esperava termos de negocio em {terms}"


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
        "01-acme-discovery.txt",
        "acme-software",
        "00000000-0000-4000-8000-000000000aaa",
    )
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Campo presente (mesmo que vazio em casos degenerados)
    assert "baselineTerms" in body, f"keys disponiveis: {list(body.keys())}"
    terms = body["baselineTerms"]
    assert isinstance(terms, list)
    assert terms, "Esperava lista nao-vazia para transcript real."

    for entry in terms:
        assert set(entry.keys()) == {"term", "score"}
        assert isinstance(entry["term"], str)
        assert isinstance(entry["score"], float | int)
        assert 0.0 <= entry["score"] <= 1.0


def test_analyze_endpoint_does_not_break_existing_contract() -> None:
    """O response continua tendo todos os campos antigos --- baselineTerms eh aditivo."""
    payload = _load_request(
        "03-northwind-renewal.txt",
        "northwind-fintech",
        "00000000-0000-4000-8000-000000000bbb",
    )
    resp = client.post("/analyze", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Campos pre-existentes mantidos
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
        assert key in body, f"campo pre-existente {key!r} sumiu do response"

    # Campo novo presente
    assert "baselineTerms" in body
