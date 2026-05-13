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


def _render_template(template: str, **variables: str) -> str:
    """Substitui placeholders {{key}} no template."""
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", value)
    return result


def _build_goal_section(req: AnalyzeRequest) -> str:
    """Renderiza secao do prompt com goal do usuario (ADR 0005).

    Sem goal, retorna instrucao explicita ao LLM pra emitir productivity=null.
    """
    if req.goal is None:
        return "Nenhum objetivo foi declarado para esta reuniao. DEVE emitir `productivity` = null."

    outcomes_md = "\n".join(f"- {o}" for o in req.goal.expected_outcomes)
    if req.goal.project_state_snapshot:
        snap = req.goal.project_state_snapshot
        state_block = f"\n\nEstado atual do projeto (informado pelo usuario):\n```\n{snap}\n```"
    else:
        state_block = ""
    return (
        f"O usuario declarou objetivo para esta reuniao. Avalie produtividade "
        f"comparando o que foi discutido com o que era esperado.\n\n"
        f"Proposito declarado: {req.goal.purpose}\n\n"
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

    tenant_ctx_json = json.dumps(
        req.tenant_context.model_dump(by_alias=True), ensure_ascii=False, indent=2
    )

    user_prompt = _render_template(
        user_template,
        tenant_context_json=tenant_ctx_json,
        meeting_id=req.meeting_id,
        language=req.language,
        transcript=req.transcript,
        goal_section=_build_goal_section(req),
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

    logger.debug("LLM raw response (first 500 chars): %s", raw_json[:500])

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
