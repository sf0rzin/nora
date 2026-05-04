"""Client OpenRouter usando OpenAI SDK compativel.

OpenRouter expoe uma API compativel com OpenAI, entao usamos o SDK oficial
com base_url customizada. Suporta structured outputs (JSON schema) quando
o modelo subjacente suporta.
"""

from __future__ import annotations

import logging
from typing import Any

from openai import OpenAI

from ..settings import Settings

logger = logging.getLogger(__name__)


class OpenRouterClient:
    def __init__(self, settings: Settings) -> None:
        if not settings.openrouter_api_key:
            raise ValueError("OPENROUTER_API_KEY e obrigatoria quando USE_LLM_STUB=false.")
        self._client = OpenAI(
            base_url=settings.openrouter_base_url,
            api_key=settings.openrouter_api_key,
        )
        self._model = settings.openrouter_model

    def chat_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        json_schema: dict[str, Any],
        schema_name: str = "meeting_analysis",
        temperature: float = 0.1,
        max_tokens: int = 4096,
    ) -> tuple[str, int, int]:
        """Envia mensagens ao modelo com structured output (JSON schema).

        Retorna (json_string, tokens_input, tokens_output).
        """
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=temperature,
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

        choice = response.choices[0]
        content = choice.message.content or "{}"

        usage = response.usage
        tokens_input = usage.prompt_tokens if usage else 0
        tokens_output = usage.completion_tokens if usage else 0

        logger.info(
            "OpenRouter call completed: model=%s tokens_in=%d tokens_out=%d",
            self._model,
            tokens_input,
            tokens_output,
        )

        return content, tokens_input, tokens_output

    def chat_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        temperature: float = 0.1,
        max_tokens: int = 4096,
    ) -> tuple[str, int, int]:
        """Envia mensagens ao modelo com JSON mode (sem schema strict).

        Fallback para modelos que nao suportam structured outputs.
        Retorna (json_string, tokens_input, tokens_output).
        """
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=temperature,
            max_tokens=max_tokens,
            response_format={"type": "json_object"},
        )

        choice = response.choices[0]
        content = choice.message.content or "{}"

        usage = response.usage
        tokens_input = usage.prompt_tokens if usage else 0
        tokens_output = usage.completion_tokens if usage else 0

        logger.info(
            "OpenRouter JSON call completed: model=%s tokens_in=%d tokens_out=%d",
            self._model,
            tokens_input,
            tokens_output,
        )

        return content, tokens_input, tokens_output


def build_json_schema_for_analysis() -> dict[str, Any]:
    """Gera o JSON Schema para structured output.

    Alinhado com MeetingAnalysisV1 do models.py.
    """
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
        ],
        "additionalProperties": False,
    }
