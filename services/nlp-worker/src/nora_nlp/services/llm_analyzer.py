"""LLM Analyzer: integra OpenRouter com pipeline de analise.

Pipeline:
  1. Carrega prompt versionado de prompts/
  2. Injeta tenant context JSON + transcricao no template
  3. Chama OpenRouter com structured output (JSON schema)
  4. Valida resposta com Pydantic MeetingAnalysisV1
  5. Retorna AnalyzeResponse com metadata de execucao
"""

from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

from ..clients.openrouter import OpenRouterClient, build_json_schema_for_analysis
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


def analyze(
    req: AnalyzeRequest,
    settings: Settings,
    *,
    pii_redactions_applied: int = 0,
) -> AnalyzeResponse:
    """Analisa transcricao via OpenRouter com saida JSON estruturada."""
    started = time.monotonic()

    client = OpenRouterClient(settings)

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
                "modelVersion": f"openrouter-{settings.openrouter_model}",
                "promptVersion": req.options.prompt_version,
                "tokensInput": tokens_in,
                "tokensOutput": tokens_out,
                "processingMillis": elapsed_ms,
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )

    return response
