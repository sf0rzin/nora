"""Live Analyzer: analise em tempo real de trechos parciais de reunioes.

Pipeline (similar ao llm_analyzer.py, mas mais leve):
  1. Carrega prompt versionado ``live-highlights-v1.md``.
  2. Injeta trecho + highlights anteriores (para dedup) no template.
  3. Chama o LLM com structured output (JSON Schema strict).
  4. Em caso de falha do strict, faz fallback para JSON mode.
  5. Valida a resposta com Pydantic (``LiveHighlightsV1``).
  6. Retorna ``LiveAnalyzeResponse`` com metadata de execucao.

Provider configurado via ``LLM_BASE_URL`` / ``LLM_API_KEY`` / ``LLM_MODEL``
(ADR 0004). Default: OpenAI direto com ``gpt-4o-mini``.
"""

from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

from ..clients.llm import LlmClient
from ..models import LiveAnalyzeRequest, LiveAnalyzeResponse, LiveAnalyzeMetadata, LiveHighlightsV1
from ..settings import Settings

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


def _load_prompt() -> tuple[str, str]:
    path = PROMPTS_DIR / "live-highlights-v1.md"
    if not path.exists():
        raise FileNotFoundError(f"Prompt nao encontrado: {path}")

    content = path.read_text(encoding="utf-8")

    system_match = re.search(r"##\s*SYSTEM\s*\n(.*?)(?=\n##\s*USER)", content, re.DOTALL)
    user_match = re.search(r"##\s*USER\s*\n(.*)", content, re.DOTALL)

    if not system_match or not user_match:
        raise ValueError("Prompt live-highlights-v1.md deve conter secoes ## SYSTEM e ## USER")

    return system_match.group(1).strip(), user_match.group(1).strip()


def _render_template(template: str, **variables: str) -> str:
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", value)
    return result


def _build_previous_highlights_section(previous: LiveHighlightsV1 | None) -> str:
    if previous is None:
        return ""
    data = previous.model_dump(by_alias=True)
    has_any = any(data.get(k) for k in ("decisions", "nextSteps", "observations", "tasks"))
    if not has_any:
        return ""
    return (
        "Destaques ja identificados anteriormente (NAO duplique):\n"
        "```json\n"
        f"{json.dumps(data, ensure_ascii=False, indent=2)}\n"
        "```\n"
    )


def _build_json_schema_for_live() -> dict:
    return {
        "type": "object",
        "properties": {
            "decisions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "confidence": {"type": "number"},
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["text", "confidence", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "nextSteps": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "confidence": {"type": "number"},
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["text", "confidence", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "observations": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "confidence": {"type": "number"},
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["text", "confidence", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "tasks": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "assignee": {"type": ["string", "null"]},
                        "priority": {
                            "type": "string",
                            "enum": ["LOW", "MEDIUM", "HIGH"],
                        },
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["title", "assignee", "priority", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["decisions", "nextSteps", "observations", "tasks"],
        "additionalProperties": False,
    }


def analyze(
    req: LiveAnalyzeRequest,
    settings: Settings,
    *,
    pii_redactions_applied: int = 0,
) -> LiveAnalyzeResponse:
    started = time.monotonic()

    client = LlmClient(settings)

    system_prompt, user_template = _load_prompt()

    previous_section = _build_previous_highlights_section(req.previous_highlights)

    user_prompt = _render_template(
        user_template,
        language=req.language,
        transcript_chunk=req.transcript_chunk,
        previous_highlights_section=previous_section,
    )

    json_schema = _build_json_schema_for_live()

    try:
        raw_json, tokens_in, tokens_out = client.chat_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            json_schema=json_schema,
            schema_name="live_highlights",
            temperature=0.1,
            max_tokens=2048,
        )
    except Exception as exc:
        logger.warning("Structured output falhou no live, tentando JSON mode fallback: %s", exc)
        raw_json, tokens_in, tokens_out = client.chat_json(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            temperature=0.1,
            max_tokens=2048,
        )

    logger.debug("Live LLM raw response (first 300 chars): %s", raw_json[:300])

    parsed = json.loads(raw_json)
    highlights = LiveHighlightsV1.model_validate(parsed)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    return LiveAnalyzeResponse.model_validate(
        {
            **highlights.model_dump(by_alias=True),
            "metadata": {
                "processingMillis": elapsed_ms,
                "tokensInput": tokens_in,
                "tokensOutput": tokens_out,
                "piiRedactionsApplied": pii_redactions_applied,
                "modelVersion": f"{settings.llm_provider}-{settings.llm_model}",
            },
        }
    )
