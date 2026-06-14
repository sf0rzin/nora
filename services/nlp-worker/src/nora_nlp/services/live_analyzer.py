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
import time

from ..clients.llm import LlmClient
from ..models import LiveAnalyzeRequest, LiveAnalyzeResponse, LiveHighlightsV1
from ..settings import Settings
from .pii_shield import redact as pii_redact
from .prompt_utils import load_prompt, render_template

logger = logging.getLogger(__name__)


_TEXT_FIELDS_HIGHLIGHTS = {
    "decisions": ("text", "sourceQuote"),
    "nextSteps": ("text", "sourceQuote"),
    "observations": ("text", "sourceQuote"),
    "tasks": ("title", "assignee", "sourceQuote"),
}


def _redact_highlights_dict(data: dict) -> tuple[dict, int]:
    """Aplica PII Shield em todos os campos de texto livre de `data`.

    `previous_highlights` vem do cliente (backend repassa, mas o conteudo
    original foi gerado pelo LLM da rodada anterior — pode conter PII que
    escapou do primeiro shield, especialmente em campos like `sourceQuote`
    que ecoam o transcript). Re-aplicar shield aqui evita amplificacao a cada
    iteracao. ADR 0012.
    """
    extra_redactions = 0
    for collection_key, text_fields in _TEXT_FIELDS_HIGHLIGHTS.items():
        items = data.get(collection_key) or []
        for item in items:
            for field in text_fields:
                value = item.get(field)
                if isinstance(value, str) and value:
                    result = pii_redact(value)
                    if result.redactions:
                        item[field] = result.redacted_text
                        extra_redactions += len(result.redactions)
    return data, extra_redactions


def _build_previous_highlights_section(
    previous: LiveHighlightsV1 | None,
) -> tuple[str, int]:
    if previous is None:
        return "", 0
    data = previous.model_dump(by_alias=True)
    data, extra = _redact_highlights_dict(data)
    has_any = any(data.get(k) for k in ("decisions", "nextSteps", "observations", "tasks"))
    if not has_any:
        return "", extra
    section = (
        "Destaques ja identificados anteriormente (NAO duplique):\n"
        "```json\n"
        f"{json.dumps(data, ensure_ascii=False, indent=2)}\n"
        "```\n"
    )
    return section, extra


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

    system_prompt, user_template = load_prompt("live-highlights-v1")

    previous_section, prev_redactions = _build_previous_highlights_section(req.previous_highlights)
    pii_redactions_applied = pii_redactions_applied + prev_redactions

    user_prompt = render_template(
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

    # NAO logar raw_json: ADR 0012 (PII never logged).
    logger.debug("Live LLM raw response: %d chars", len(raw_json))

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
