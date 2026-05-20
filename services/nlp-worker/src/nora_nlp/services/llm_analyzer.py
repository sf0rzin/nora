"""LLM Analyzer: pipeline de análise via cliente LLM agnóstico.

Pipeline:
  1. Carrega prompt versionado de ``prompts/``.
  2. Injeta tenant context JSON + transcrição no template.
  3. Chama o LLM com structured output (JSON Schema strict).
  4. Em caso de falha do strict, faz fallback para JSON mode.
  5. Valida a resposta com Pydantic (``MeetingAnalysisV1``).
  6. Retorna ``AnalyzeResponse`` com metadata de execução.

Provider configurado via ``LLM_BASE_URL`` / ``LLM_API_KEY`` / ``LLM_MODEL``
(ADR 0004). Default: OpenAI direto com ``gpt-4o-mini``.
"""

from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

from ..clients.llm import LlmClient, build_json_schema_for_analysis
from ..models import AnalyzeRequest, AnalyzeResponse, MeetingAnalysisV1
from ..settings import Settings
from .pii_shield import redact as pii_redact

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


def _load_prompt(version: str) -> tuple[str, str]:
    """Carrega o prompt versionado e retorna (system, user) sections."""
    filename = f"{version}.md"
    path = PROMPTS_DIR / filename
    if not path.exists():
        raise FileNotFoundError(f"Prompt nao encontrado: {path}")

    content = path.read_text(encoding="utf-8")

    system_match = re.search(r"##\s*SYSTEM\s*\n(.*?)(?=\n##\s*USER)", content, re.DOTALL)
    user_match = re.search(r"##\s*USER\s*\n(.*)", content, re.DOTALL)

    if not system_match or not user_match:
        raise ValueError(f"Prompt {filename} deve conter secoes ## SYSTEM e ## USER")

    return system_match.group(1).strip(), user_match.group(1).strip()


_TEMPLATE_PLACEHOLDER_RE = re.compile(r"\{\{[^{}]+\}\}")


def _escape_placeholders(value: str) -> str:
    """Neutraliza placeholders `{{x}}` vindos do usuario.

    `_render_template` faz str.replace em ordem; se o transcript ou outro
    campo do request contiver `{{goal_section}}`, ele seria substituido pelo
    conteudo legitimo de goal_section nas iteracoes seguintes — prompt
    template injection. Trocar `{{` por `{ {` rompe o casamento sem mudar a
    intencao semantica do texto original.
    """
    if "{{" not in value:
        return value
    return value.replace("{{", "{ {").replace("}}", "} }")


def _render_template(template: str, **variables: str) -> str:
    """Substitui placeholders {{key}} no template, com escape de input do usuario."""
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", _escape_placeholders(value))
    return result


def _shield_field(value: str, counter: list[int]) -> str:
    """Aplica PII Shield em campo individual, contabilizando redactions."""
    if not value:
        return value
    out = pii_redact(value)
    counter[0] += len(out.redactions)
    return out.redacted_text


def _build_goal_section(req: AnalyzeRequest, redaction_counter: list[int]) -> str:
    """Renderiza secao do prompt com goal do usuario (ADR 0005).

    Sem goal, retorna instrucao explicita ao LLM pra emitir productivity=null.
    Aplica PII Shield em `purpose`, `expected_outcomes` e `project_state_snapshot`
    porque sao campos de texto livre do usuario que iam crus pro LLM (violacao
    do ADR 0012 antes deste PR).
    """
    if req.goal is None:
        return "Nenhum objetivo foi declarado para esta reuniao. DEVE emitir `productivity` = null."

    purpose = _shield_field(req.goal.purpose, redaction_counter)
    outcomes_shielded = [_shield_field(o, redaction_counter) for o in req.goal.expected_outcomes]
    outcomes_md = "\n".join(f"- {o}" for o in outcomes_shielded)
    if req.goal.project_state_snapshot:
        snap = _shield_field(req.goal.project_state_snapshot, redaction_counter)
        state_block = f"\n\nEstado atual do projeto (informado pelo usuario):\n```\n{snap}\n```"
    else:
        state_block = ""
    return (
        f"O usuario declarou objetivo para esta reuniao. Avalie produtividade "
        f"comparando o que foi discutido com o que era esperado.\n\n"
        f"Proposito declarado: {purpose}\n\n"
        f"Outcomes esperados:\n{outcomes_md}{state_block}\n\n"
        f"Para cada outcome esperado, classifique como ADDRESSED, PARTIAL ou MISSED "
        f"e cite evidencia textual da transcricao. Calcule o score (0-100) priorizando "
        f"cobertura de outcomes; bandas: LOW (<40), MEDIUM (40-69), HIGH (>=70). "
        f"Inclua offTopicRatio e decisionDensity como floats 0-1 (ou null se nao puder estimar). "
        f"DEVE emitir o campo `productivity` populado (nao null)."
    )


def analyze(
    req: AnalyzeRequest,
    settings: Settings,
    *,
    pii_redactions_applied: int = 0,
) -> AnalyzeResponse:
    """Analisa transcrição via LLM com saída JSON estruturada."""
    started = time.monotonic()

    client = LlmClient(settings)

    system_prompt, user_template = _load_prompt(req.options.prompt_version)

    # tenant_context tem campos longos de texto livre (companyName, products,
    # valueProposition, objectionHandling, glossary) — defesa-em-profundidade
    # contra um tenant que cole PII no contexto comercial.
    extra_redactions = [0]
    ctx_dict = req.tenant_context.model_dump(by_alias=True)
    for k in ("companyName", "valueProposition", "idealCustomerProfile", "objectionHandling"):
        v = ctx_dict.get(k)
        if isinstance(v, str) and v:
            ctx_dict[k] = _shield_field(v, extra_redactions)
    products = ctx_dict.get("products") or []
    for p in products:
        if isinstance(p, dict):
            for k in ("name", "description"):
                v = p.get(k)
                if isinstance(v, str) and v:
                    p[k] = _shield_field(v, extra_redactions)
    glossary = ctx_dict.get("glossary") or []
    for g in glossary:
        if isinstance(g, dict):
            for k in ("term", "meaning"):
                v = g.get(k)
                if isinstance(v, str) and v:
                    g[k] = _shield_field(v, extra_redactions)
    competitors = ctx_dict.get("competitors") or []
    if isinstance(competitors, list):
        ctx_dict["competitors"] = [
            _shield_field(c, extra_redactions) if isinstance(c, str) else c for c in competitors
        ]

    tenant_ctx_json = json.dumps(ctx_dict, ensure_ascii=False, indent=2)

    goal_section = _build_goal_section(req, extra_redactions)

    pii_redactions_applied = pii_redactions_applied + extra_redactions[0]

    user_prompt = _render_template(
        user_template,
        tenant_context_json=tenant_ctx_json,
        meeting_id=req.meeting_id,
        language=req.language,
        transcript=req.transcript,
        goal_section=goal_section,
    )

    json_schema = build_json_schema_for_analysis()

    try:
        raw_json, tokens_in, tokens_out = client.chat_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            json_schema=json_schema,
            schema_name="meeting_analysis",
        )
    except Exception as exc:
        logger.warning("Structured output falhou, tentando JSON mode fallback: %s", exc)
        raw_json, tokens_in, tokens_out = client.chat_json(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
        )

    # NAO logar raw_json: pode conter PII residual que escapou do shield em
    # forma de texto ecoado em sourceQuote. ADR 0012 (PII never logged).
    logger.debug("LLM raw response: %d chars", len(raw_json))

    parsed = json.loads(raw_json)
    analysis = MeetingAnalysisV1.model_validate(parsed)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    response = AnalyzeResponse.model_validate(
        {
            **analysis.model_dump(by_alias=True),
            "meetingId": req.meeting_id,
            "metadata": {
                "modelVersion": f"{settings.llm_provider}-{settings.llm_model}",
                "promptVersion": req.options.prompt_version,
                "tokensInput": tokens_in,
                "tokensOutput": tokens_out,
                "processingMillis": elapsed_ms,
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )

    return response
