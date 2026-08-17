"""Tests for the LLM analyzer with a mocked LLM client.

Validates the full pipeline (prompt loading -> tenant context injection ->
LLM call -> Pydantic validation) without real API cost.
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
    # The contract is that the count handed in (from the transcript stage) is carried and
    # ADDED to whatever the tenant context itself contributes. Asserting the literal 3 also
    # asserted, incidentally, that this fixture yields no context redactions — which stopped
    # being true once every free-text leaf of the context started reaching the shield
    # (ADR 0012): the fixture's marketing copy trips the Title Case name heuristic, and
    # over-redacting is the direction the shield deliberately fails in.
    from_tenant_context = analyze(req, settings).metadata.pii_redactions_applied
    assert response.metadata.pii_redactions_applied == 3 + from_tenant_context
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
    """Regression: the glossary `meaning` field was ignored (the code iterated over
    `definition`, which does not exist in the model). PII pasted by the tenant reached
    the LLM raw via glossary, violating ADR 0012.
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


def _tenant_ctx_with_pii_in_every_free_text_field() -> dict:
    """acme fixture with PII pasted into the fields that never reached the shield.

    Additive on purpose (append, not replace) so the redaction count of this context is the
    count of the pristine fixture plus exactly what is injected here.
    """
    ctx = json.loads(
        (DATA_DIR / "tenants" / "acme-software.context.json").read_text(encoding="utf-8")
    )
    ctx["industry"] = "Consultoria fiscal conduzida por Mariana Cardoso"
    ctx["objectionHandling"].append(
        "Quando o cliente pedir referencia: falar com Ricardo Almeida, CPF 111.444.777-35."
    )
    ctx["products"][0]["keyDifferentiators"].append(
        "Implantacao acompanhada por Fernanda Barbosa (fernanda.barbosa@acme.com.br)"
    )
    return ctx


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_analyze_sanitizes_pii_in_every_tenant_context_free_text_field(MockClient):
    """Regression: the shield was driven by a hand-kept list of field names, so it only
    covered the shape that list was written against. `objectionHandling` was listed but is a
    `list[str]`, and the guard on the value accepted a `str` only — it never fired. `industry`
    and `products[].keyDifferentiators` were not listed at all. Those three fields went into
    the prompt raw and contributed nothing to `piiRedactionsApplied`, so the audit trail read
    clean for text nobody had inspected. Violation of ADR 0012.
    """
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (json.dumps(_FAKE_LLM_RESPONSE), 100, 50)
    MockClient.return_value = mock_instance
    settings = _make_settings()

    baseline = analyze(_make_request(), settings).metadata.pii_redactions_applied

    transcript = (DATA_DIR / "meetings" / "01-acme-discovery-lead-novo.txt").read_text(
        encoding="utf-8"
    )
    req = AnalyzeRequest(
        meetingId="test-meeting-context-fields",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript=transcript,
        tenantContext=_tenant_ctx_with_pii_in_every_free_text_field(),
    )
    response = analyze(req, settings)

    # Asserted on the payload actually handed to the provider, not on an intermediate.
    user_prompt = mock_instance.chat_structured.call_args.kwargs["user_prompt"]
    for raw in (
        "Mariana Cardoso",
        "Ricardo Almeida",
        "111.444.777-35",
        "Fernanda Barbosa",
        "fernanda.barbosa@acme.com.br",
    ):
        assert raw not in user_prompt
    assert "[[PERSON_NAME_1]]" in user_prompt
    assert "[[CPF_1]]" in user_prompt
    assert "[[EMAIL_1]]" in user_prompt
    # The audit trail has to grow with the newly shielded fields: 3 names + CPF + e-mail.
    assert response.metadata.pii_redactions_applied >= baseline + 5


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
    # Without the block in the payload, the nullable default keeps the contract (ADR 0006).
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
    """LLM emits customerConfidence -> Pydantic validates and the detected accountName lives."""
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
    # Serializes back with camelCase aliases (backend contract).
    dumped = response.model_dump(by_alias=True)
    assert dumped["customerConfidence"]["accountName"] == "ACME Manufatura S.A."
    assert dumped["customerConfidence"]["buyingSignals"][0]["type"] == "PROPOSAL_REQUESTED"


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_the_prompt_does_not_contradict_itself_about_the_tenants_own_name(MockClient):
    """Finding 5c, in the half that was left undone until review pointed at it.

    The transcript keeps the tenant's trade name -- that is the whole feature -- but the
    tenant-context block was shielded with NO tenant terms, so a multi-word name like
    "Kranz Solutions" came back as `[[PERSON_NAME_1]]` in the context JSON while surviving
    verbatim in the transcript of the same request. One entity, two spellings, one prompt.

    Over-redaction, never a leak, which is why it was classed as a coherence defect rather
    than a security one. It is still the feature only half delivered.

    The assertion checks the CONTEXT BLOCK specifically, not just the prompt as a whole:
    the name is in the transcript either way, so `"Kranz Solutions" in user_prompt` would
    pass while the bug was live.
    """
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (json.dumps(_FAKE_LLM_RESPONSE), 100, 50)
    MockClient.return_value = mock_instance

    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / "acme-software.context.json").read_text(encoding="utf-8")
    )
    tenant_ctx["companyName"] = "Kranz Solutions"

    req = AnalyzeRequest(
        meetingId="test-meeting-5c-coherence",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript="Kranz Solutions fechou o contrato com a equipe hoje.",
        tenantContext=tenant_ctx,
    )

    analyze(req, _make_settings())
    user_prompt = mock_instance.chat_structured.call_args.kwargs["user_prompt"]

    # The context block is the JSON object; isolate it so the transcript cannot satisfy this.
    start = user_prompt.index('"companyName"')
    context_slice = user_prompt[start : start + 200]
    assert "Kranz Solutions" in context_slice, (
        "the tenant's own name was redacted out of the context block while surviving in the "
        f"transcript -- the prompt contradicts itself. Context said: {context_slice[:120]!r}"
    )

    # ...and the shield is still deciding: a person in the context is still redacted.
    tenant_ctx_with_person = dict(tenant_ctx)
    tenant_ctx_with_person["industry"] = "Consultoria conduzida por Mariana Cardoso"
    req2 = AnalyzeRequest(
        meetingId="test-meeting-5c-coherence-2",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript="Kranz Solutions fechou o contrato com a equipe hoje.",
        tenantContext=tenant_ctx_with_person,
    )
    analyze(req2, _make_settings())
    prompt2 = mock_instance.chat_structured.call_args.kwargs["user_prompt"]
    assert "Mariana Cardoso" not in prompt2, (
        "a person pasted into the tenant context reached the provider -- the terms are not "
        "supposed to weaken the shield, only to stop it eating the trade name"
    )


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_a_relative_due_date_does_not_destroy_the_analysis(MockClient):
    """Regression: production lost every analysis of a Portuguese meeting.

    The model was never told what shape `dueDate` takes -- the copy of the schema sent to
    the provider had dropped the `format: date` that the canonical schema carries -- so it
    answered with the words it heard: `'sexta-feira'` and `'amanha'`. Pydantic raised,
    the router returned 503, and the API marked the meeting FAILED. The summary, the
    decisions, the risks and the opportunities were all discarded because one OPTIONAL
    field could not be parsed.

    The instruction is now in the prompt and in the schema, but no instruction is a
    guarantee, so the parser has to survive being disobeyed.
    """
    payload = json.loads(json.dumps(_FAKE_LLM_RESPONSE))
    payload["actionItems"] = [
        {
            "title": "Enviar o business case consolidado",
            "assignee": "Ana",
            "dueDate": "sexta-feira",
            "priority": "HIGH",
            "sourceQuote": "Eu vou enviar o business case consolidado ate sexta-feira.",
        },
        {
            "title": "Confirmar a disponibilidade do time de implantacao",
            "assignee": "Bruno",
            "dueDate": "amanha",
            "priority": "MEDIUM",
            "sourceQuote": "Vou confirmar com o time de implantacao e retorno amanha.",
        },
        {
            "title": "Levar a proposta ao comite financeiro",
            "assignee": "Carla",
            "dueDate": "2026-09-01",
            "priority": "HIGH",
            "sourceQuote": "Preciso levar ao comite financeiro na proxima terca.",
        },
    ]
    mock_instance = MagicMock()
    mock_instance.chat_structured.return_value = (json.dumps(payload), 1500, 800)
    MockClient.return_value = mock_instance

    response = analyze(_make_request(), _make_settings())

    # The analysis survived, which is the whole point.
    assert response.summary.startswith("## Objetivo")
    assert len(response.decisions) == 1
    assert len(response.risks) == 1
    assert len(response.opportunities) == 1

    # Every action item survived too, titles and quotes intact -- the commitment is still
    # recorded even where the date could not be.
    assert len(response.action_items) == 3
    assert response.action_items[0].due_date is None
    assert response.action_items[1].due_date is None
    assert "business case" in response.action_items[0].title
    assert "amanha" in response.action_items[1].source_quote

    # An absolute date is still parsed. Dropping the field wholesale would have been the
    # other way to make this stop failing, and it would have been the wrong one.
    assert response.action_items[2].due_date is not None
    assert response.action_items[2].due_date.isoformat() == "2026-09-01"


def test_the_schema_sent_to_the_provider_states_the_date_format():
    """The two copies of the analysis schema must not drift on `dueDate` again.

    `docs/api/llm-schemas/meeting-analysis-v1.schema.json` constrains it with
    `"format": "date"`; `build_json_schema_for_analysis` is a hand-maintained duplicate that
    had silently lost the constraint. `format` is outside the strict structured-output
    subset, so the constraint travels as a description -- but it has to travel.
    """
    from nora_nlp.clients.llm import build_json_schema_for_analysis

    due = build_json_schema_for_analysis()["properties"]["actionItems"]["items"]["properties"][
        "dueDate"
    ]
    described = due.get("description", "")
    assert "YYYY-MM-DD" in described, (
        "the schema handed to the model does not say what a dueDate looks like; "
        f"got {due!r}"
    )
    assert "null" in described, "the schema does not tell the model when to give up and use null"
