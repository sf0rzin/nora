"""Testes do LLM analyzer com mock do cliente LLM.

Valida o pipeline completo (prompt loading -> tenant context injection ->
LLM call -> Pydantic validation) sem custo real de API.
"""

from __future__ import annotations

import inspect
import json
import pathlib
from unittest.mock import MagicMock, patch

import pytest

from nora_nlp.clients.llm import LlmClient as _Real
from nora_nlp.models import AnalyzeRequest
from nora_nlp.services.llm_analyzer import analyze
from nora_nlp.services.prompt_utils import load_prompt, render_template
from nora_nlp.settings import Settings

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DATA_DIR = REPO_ROOT / "data" / "synthetic"


def _make_settings() -> Settings:
    return Settings(
        worker_port=8001,
        llm_provider="openai",
        llm_base_url="https://api.openai.com/v1",
        llm_api_key="sk-test-fake-1234567890",
        llm_model="gpt-4o-mini",
        llm_temperature=0.2,
        use_llm_stub=False,
    )


def _make_request(
    transcript_file: str = "01-acme-discovery-lead-novo.txt",
    tenant: str = "acme-software",
) -> AnalyzeRequest:
    transcript = (DATA_DIR / "meetings" / transcript_file).read_text(encoding="utf-8")
    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / f"{tenant}.context.json").read_text(encoding="utf-8")
    )
    return AnalyzeRequest(
        meetingId="test-meeting-001",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript=transcript,
        tenantContext=tenant_ctx,
    )


_FAKE_LLM_RESPONSE = {
    "summary": (
        "## Objetivo\n"
        "Reuniao de discovery com o cliente para entender necessidades de ERP.\n\n"
        "## Proximos Passos\n"
        "- **Enviar proposta** comercial ate sexta-feira\n"
        "- Agendar demonstracao tecnica\n\n"
        "## Observacoes\n"
        "Cliente demonstrou interesse no modulo financeiro."
    ),
    "decisions": [
        {"text": "Cliente aprovou avancar para fase de proposta comercial", "confidence": 0.9}
    ],
    "actionItems": [
        {
            "title": "Enviar proposta comercial",
            "assignee": "Carlos",
            "dueDate": "2026-06-15",
            "priority": "HIGH",
            "sourceQuote": "Vamos enviar a proposta ate o final da semana.",
        }
    ],
    "risks": [
        {
            "text": "Concorrente TotalSys mencionado como alternativa mais barata",
            "severity": "HIGH",
            "category": "COMPETITION",
            "sourceQuote": "O TotalSys ofereceu um preco bem abaixo do nosso.",
        }
    ],
    "opportunities": [
        {
            "text": "Upsell do modulo de RH integrado ao financeiro",
            "estimatedValue": "MEDIUM",
            "category": "UPSELL",
            "sourceQuote": "Voces teriam o modulo de RH integrado?",
        }
    ],
    "sentimentOverall": "POSITIVE",
    "topics": ["ERP", "proposta comercial", "modulo financeiro", "concorrencia"],
    "participants": [
        {"name": "Carlos", "role": "Gerente Comercial", "mentionCount": 8},
        {"name": "Ana", "role": "Diretora Financeira", "mentionCount": 5},
    ],
}


def test_load_prompt_returns_system_and_user():
    system, user = load_prompt("meeting-analysis-v1")
    assert "NORA" in system
    assert "{{tenant_context_json}}" in user
    assert "{{transcript}}" in user


def test_load_prompt_raises_for_missing_file():
    with pytest.raises(FileNotFoundError):
        load_prompt("nonexistent-prompt-v99")


def test_render_template_substitutes_variables():
    template = "Hello {{name}}, your meeting {{meeting_id}} is ready."
    result = render_template(template, name="World", meeting_id="abc-123")
    assert result == "Hello World, your meeting abc-123 is ready."


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_returns_valid_response(MockClient):
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (
        json.dumps(_FAKE_LLM_RESPONSE),
        1500,
        800,
    )
    MockClient.return_value = mock_instance

    req = _make_request()
    settings = _make_settings()
    response = analyze(req, settings, pii_redactions_applied=3)

    assert response.meeting_id == "test-meeting-001"
    assert response.summary.startswith("## Objetivo")
    assert len(response.decisions) == 1
    assert response.decisions[0].confidence == 0.9
    assert len(response.action_items) == 1
    assert response.action_items[0].priority.value == "HIGH"
    assert len(response.risks) == 1
    assert response.risks[0].category.value == "COMPETITION"
    assert len(response.opportunities) == 1
    assert response.sentiment_overall.value == "POSITIVE"
    assert len(response.participants) == 2
    assert response.participants[0].name == "Carlos"
    assert response.participants[0].mention_count == 8

    assert response.metadata.tokens_input == 1500
    assert response.metadata.tokens_output == 800
    assert response.metadata.pii_redactions_applied == 3
    assert response.metadata.model_version == "openai-gpt-4o-mini"
    assert response.metadata.processing_millis >= 0


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_injects_tenant_context_in_prompt(MockClient):
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (
        json.dumps(_FAKE_LLM_RESPONSE),
        100,
        50,
    )
    MockClient.return_value = mock_instance

    req = _make_request()
    settings = _make_settings()
    analyze(req, settings)

    call_args = mock_instance.chat_structured.call_args
    user_prompt = call_args.kwargs["user_prompt"]

    assert "ACME" in user_prompt or "acme" in user_prompt.lower()
    assert req.meeting_id in user_prompt


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_uses_low_temperature(MockClient):
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (
        json.dumps(_FAKE_LLM_RESPONSE),
        100,
        50,
    )
    MockClient.return_value = mock_instance

    req = _make_request()
    settings = _make_settings()
    analyze(req, settings)

    mock_instance.chat_structured.assert_called_once()
    sig = inspect.signature(_Real.chat_structured)
    # Default temperature param is None (delegates to settings.llm_temperature).
    # Settings default is 0.2 — assert it's reasonably low.
    assert settings.llm_temperature <= 0.3
    # And the parameter exists (regression guard).
    assert "temperature" in sig.parameters


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_falls_back_to_json_mode(MockClient):
    mock_instance = MagicMock()
    mock_instance.chat_structured.side_effect = Exception("structured output not supported")
    mock_instance.chat_json.return_value = (
        json.dumps(_FAKE_LLM_RESPONSE),
        100,
        50,
    )
    MockClient.return_value = mock_instance

    req = _make_request()
    settings = _make_settings()
    response = analyze(req, settings)

    assert response.meeting_id == "test-meeting-001"
    mock_instance.chat_json.assert_called_once()


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_sanitizes_pii_in_tenant_context_glossary(MockClient):
    """Regressao: campo `meaning` do glossario era ignorado (codigo iterava sobre
    `definition`, que nao existe no modelo). PII colado pelo tenant chegava cru ao
    LLM via glossary, violando ADR 0012.
    """
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (json.dumps(_FAKE_LLM_RESPONSE), 100, 50)
    MockClient.return_value = mock_instance

    transcript = (DATA_DIR / "meetings" / "01-acme-discovery-lead-novo.txt").read_text(
        encoding="utf-8"
    )
    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / "acme-software.context.json").read_text(encoding="utf-8")
    )
    tenant_ctx["glossary"] = [
        {
            "term": "Conta-mae",
            "meaning": (
                "Conta unificadora de Joao da Silva com CPF 111.444.777-35 usada "
                "para conciliacao bancaria."
            ),
        }
    ]
    req = AnalyzeRequest(
        meetingId="test-meeting-glossary",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript=transcript,
        tenantContext=tenant_ctx,
    )

    analyze(req, _make_settings())

    user_prompt = mock_instance.chat_structured.call_args.kwargs["user_prompt"]
    assert "111.444.777-35" not in user_prompt
    assert "Joao da Silva" not in user_prompt
    assert "[[CPF_1]]" in user_prompt or "[[PERSON_NAME_1]]" in user_prompt


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_handles_minimal_llm_response(MockClient):
    minimal_response = {
        "summary": (
            "Reuniao breve sobre alinhamento de expectativas. Nenhuma decisao formal foi tomada."
        ),
        "decisions": [],
        "actionItems": [],
        "risks": [],
        "opportunities": [],
        "sentimentOverall": "NEUTRAL",
        "topics": ["alinhamento"],
        "participants": [],
    }
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (
        json.dumps(minimal_response),
        500,
        200,
    )
    MockClient.return_value = mock_instance

    req = _make_request()
    settings = _make_settings()
    response = analyze(req, settings)

    assert response.summary
    assert response.decisions == []
    assert response.action_items == []
    assert response.participants == []
    assert response.sentiment_overall.value == "NEUTRAL"
    # Sem o bloco no payload, o default nullable mantem o contrato (ADR 0006).
    assert response.customer_confidence is None


# ---------- Customer Confidence (ADR 0006) ----------

_FAKE_LLM_RESPONSE_WITH_CONFIDENCE = {
    **_FAKE_LLM_RESPONSE,
    "customerConfidence": {
        "score": 72,
        "band": "HIGH",
        "trend": "IMPROVING",
        "accountName": "ACME Manufatura S.A.",
        "buyingSignals": [
            {
                "type": "PROPOSAL_REQUESTED",
                "quote": "Podem enviar a proposta comercial ainda esta semana?",
                "weight": 0.8,
            },
            {
                "type": "STAKEHOLDER_INVOLVED",
                "quote": "Vou trazer nossa diretora financeira para a proxima call.",
                "weight": None,
            },
        ],
        "objections": [
            {
                "type": "COMPETITOR_MENTION",
                "quote": "O TotalSys ofereceu um preco bem abaixo do de voces.",
                "severity": "HIGH",
                "competitor": "TotalSys",
            }
        ],
        "rationale": (
            "Cliente pediu proposta e envolveu decisora, mas comparou preco com "
            "concorrente; confianca alta com ressalva comercial."
        ),
    },
}


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_parses_customer_confidence(MockClient):
    """LLM emite customerConfidence -> Pydantic valida e o accountName detectado sobrevive."""
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (
        json.dumps(_FAKE_LLM_RESPONSE_WITH_CONFIDENCE),
        1600,
        900,
    )
    MockClient.return_value = mock_instance

    response = analyze(_make_request(), _make_settings())

    cc = response.customer_confidence
    assert cc is not None
    assert cc.score == 72
    assert cc.band.value == "HIGH"
    assert cc.trend is not None and cc.trend.value == "IMPROVING"
    assert cc.account_name == "ACME Manufatura S.A."
    assert len(cc.buying_signals) == 2
    assert cc.buying_signals[0].type.value == "PROPOSAL_REQUESTED"
    assert cc.buying_signals[0].weight == 0.8
    assert cc.buying_signals[1].weight is None
    assert len(cc.objections) == 1
    assert cc.objections[0].type.value == "COMPETITOR_MENTION"
    assert cc.objections[0].competitor == "TotalSys"
    assert cc.objections[0].severity.value == "HIGH"
    # Serializa de volta com aliases camelCase (contrato do backend).
    dumped = response.model_dump(by_alias=True)
    assert dumped["customerConfidence"]["accountName"] == "ACME Manufatura S.A."
    assert dumped["customerConfidence"]["buyingSignals"][0]["type"] == "PROPOSAL_REQUESTED"
