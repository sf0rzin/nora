"""Cliente LLM agnóstico de provider (default OpenAI direto).

Conforme ADR 0004, o worker NORA fala com qualquer provider compatível com a
API Chat Completions da OpenAI (OpenAI direto, Azure OpenAI, Groq, OpenRouter,
Ollama, etc.). A escolha é feita por variáveis de ambiente:

- ``LLM_BASE_URL`` (default ``https://api.openai.com/v1``)
- ``LLM_API_KEY``
- ``LLM_MODEL``  (default ``gpt-4o-mini``)
- ``LLM_TEMPERATURE``

Usamos a SDK oficial ``openai`` apontando para a ``base_url`` configurada.
"""

from __future__ import annotations

import logging
from typing import Any

from openai import OpenAI

from ..settings import Settings

logger = logging.getLogger(__name__)


class LlmClient:
    """Wrapper sobre a SDK OpenAI com base_url plugável."""

    def __init__(self, settings: Settings) -> None:
        if not settings.llm_api_key:
            raise ValueError("LLM_API_KEY é obrigatória quando USE_LLM_STUB=false.")
        self._client = OpenAI(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key,
        )
        self._model = settings.llm_model
        self._provider = settings.llm_provider
        self._default_temperature = settings.llm_temperature

    @property
    def model(self) -> str:
        return self._model

    @property
    def provider(self) -> str:
        return self._provider

    def chat_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        json_schema: dict[str, Any],
        schema_name: str = "meeting_analysis",
        temperature: float | None = None,
        max_tokens: int = 4096,
    ) -> tuple[str, int, int]:
        """Chama o modelo com JSON Schema strict. Retorna (json, in, out)."""
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=self._default_temperature if temperature is None else temperature,
            max_tokens=max_tokens,
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": schema_name,
                    "strict": True,
                    "schema": json_schema,
                },
            },
        )
        return self._extract(response, mode="json_schema")

    def chat_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        temperature: float | None = None,
        max_tokens: int = 4096,
    ) -> tuple[str, int, int]:
        """Fallback para JSON mode (sem strict schema)."""
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=self._default_temperature if temperature is None else temperature,
            max_tokens=max_tokens,
            response_format={"type": "json_object"},
        )
        return self._extract(response, mode="json_object")

    def _extract(self, response: Any, *, mode: str) -> tuple[str, int, int]:
        choice = response.choices[0]
        content = choice.message.content or "{}"
        usage = response.usage
        tokens_input = usage.prompt_tokens if usage else 0
        tokens_output = usage.completion_tokens if usage else 0
        logger.info(
            "LLM call completed: provider=%s model=%s mode=%s tokens_in=%d tokens_out=%d",
            self._provider,
            self._model,
            mode,
            tokens_input,
            tokens_output,
        )
        return content, tokens_input, tokens_output


def build_json_schema_for_analysis() -> dict[str, Any]:
    """JSON Schema do MeetingAnalysisV1 para structured output strict."""
    return {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "decisions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "confidence": {"type": "number"},
                    },
                    "required": ["text", "confidence"],
                    "additionalProperties": False,
                },
            },
            "actionItems": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "assignee": {"type": ["string", "null"]},
                        "dueDate": {"type": ["string", "null"]},
                        "priority": {
                            "type": "string",
                            "enum": ["LOW", "MEDIUM", "HIGH"],
                        },
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["title", "assignee", "dueDate", "priority", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "risks": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "severity": {
                            "type": "string",
                            "enum": ["LOW", "MEDIUM", "HIGH"],
                        },
                        "category": {
                            "type": "string",
                            "enum": [
                                "COMPETITION",
                                "PRICE",
                                "CHURN",
                                "TIMELINE",
                                "TECHNICAL",
                                "COMPLIANCE",
                                "OTHER",
                            ],
                        },
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["text", "severity", "category", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "opportunities": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "estimatedValue": {
                            "type": "string",
                            "enum": ["LOW", "MEDIUM", "HIGH"],
                        },
                        "category": {
                            "type": "string",
                            "enum": ["UPSELL", "CROSS_SELL", "REFERRAL", "EXPANSION", "OTHER"],
                        },
                        "sourceQuote": {"type": "string"},
                    },
                    "required": ["text", "estimatedValue", "category", "sourceQuote"],
                    "additionalProperties": False,
                },
            },
            "sentimentOverall": {
                "type": "string",
                "enum": ["POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED"],
            },
            "topics": {
                "type": "array",
                "items": {"type": "string"},
            },
            "participants": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string"},
                        "role": {"type": ["string", "null"]},
                        "mentionCount": {"type": "integer"},
                    },
                    "required": ["name", "role", "mentionCount"],
                    "additionalProperties": False,
                },
            },
            # Productivity opcional (ADR 0005). Quando o usuario nao declarou goal,
            # o prompt deve instruir o LLM a emitir productivity = null.
            "productivity": {
                "type": ["object", "null"],
                "properties": {
                    "score": {"type": "integer"},
                    "band": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                    "coverage": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "expectedOutcome": {"type": "string"},
                                "status": {
                                    "type": "string",
                                    "enum": ["ADDRESSED", "PARTIAL", "MISSED"],
                                },
                                "evidence": {"type": ["string", "null"]},
                            },
                            "required": ["expectedOutcome", "status", "evidence"],
                            "additionalProperties": False,
                        },
                    },
                    "offTopicRatio": {"type": ["number", "null"]},
                    "decisionDensity": {"type": ["number", "null"]},
                    "rationale": {"type": "string"},
                },
                "required": [
                    "score",
                    "band",
                    "coverage",
                    "offTopicRatio",
                    "decisionDensity",
                    "rationale",
                ],
                "additionalProperties": False,
            },
        },
        "required": [
            "summary",
            "decisions",
            "actionItems",
            "risks",
            "opportunities",
            "sentimentOverall",
            "topics",
            "participants",
            "productivity",
        ],
        "additionalProperties": False,
    }
