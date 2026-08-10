"""LLM Analyzer: analysis pipeline via the agnostic LLM client.

Pipeline:
  1. Loads the versioned prompt from ``prompts/``.
  2. Injects tenant context JSON + transcript into the template.
  3. Calls the LLM with structured output (strict JSON Schema).
  4. On strict failure, falls back to JSON mode.
  5. Validates the response with Pydantic (``MeetingAnalysisV1``).
  6. Returns ``AnalyzeResponse`` with execution metadata.

Provider configured via ``LLM_BASE_URL`` / ``LLM_API_KEY`` / ``LLM_MODEL``
(ADR 0004). Default: OpenAI direct with ``gpt-4o-mini``.
"""

from __future__ import annotations

import json
import logging
import time

from ..clients.llm import LlmClient, build_json_schema_for_analysis
from ..models import AnalyzeRequest, AnalyzeResponse, MeetingAnalysisV1
from ..settings import Settings
from .pii_shield import redact as pii_redact
from .prompt_utils import load_prompt, render_template

logger = logging.getLogger(__name__)


def _shield_field(value: str, counter: list[int]) -> str:
    """Applies PII Shield to an individual field, counting redactions."""
    if not value:
        return value
    out = pii_redact(value)
    counter[0] += len(out.redactions)
    return out.redacted_text


def _shield_tree(value: object, counter: list[int]) -> object:
    """Applies the PII Shield to every string leaf of a nested structure.

    Walks dicts and lists instead of naming the fields to cover. The tenant context is
    tenant-authored free text from end to end, and a hand-kept list of keys only protects
    the shape it was written against: a field of a type the list did not expect, or one
    added to ``TenantContext`` afterwards, stops reaching the shield without anything
    failing — and the redaction counter then reports a clean audit trail for text that
    was never inspected. Dict keys are schema names, not tenant input, so they are kept
    as they are. ADR 0012.
    """
    if isinstance(value, str):
        return _shield_field(value, counter)
    if isinstance(value, dict):
        return {k: _shield_tree(v, counter) for k, v in value.items()}
    if isinstance(value, list):
        return [_shield_tree(item, counter) for item in value]
    return value


def _build_goal_section(req: AnalyzeRequest, redaction_counter: list[int]) -> str:
    """Renders the prompt section with the user's goal (ADR 0005).

    Without a goal, returns an explicit instruction to the LLM to emit productivity=null.
    Applies PII Shield to `purpose`, `expected_outcomes` and `project_state_snapshot`
    because they are user free-text fields that went raw to the LLM (violation
    of ADR 0012 before this PR).
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
    """Analyzes the transcript via LLM with structured JSON output."""
    started = time.monotonic()

    client = LlmClient(settings)

    system_prompt, user_template = load_prompt(req.options.prompt_version)

    # tenant_context is long free text throughout (companyName, industry, products,
    # valueProposition, objectionHandling, competitors, glossary) — every string leaf of it
    # goes through the shield, as defense-in-depth against a tenant that pastes PII into the
    # commercial context. The walk covers the whole structure so a field added to
    # TenantContext later is protected by default instead of by remembering to list it.
    extra_redactions = [0]
    ctx_dict = _shield_tree(req.tenant_context.model_dump(by_alias=True), extra_redactions)

    tenant_ctx_json = json.dumps(ctx_dict, ensure_ascii=False, indent=2)

    goal_section = _build_goal_section(req, extra_redactions)

    pii_redactions_applied = pii_redactions_applied + extra_redactions[0]

    user_prompt = render_template(
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
        logger.warning("Structured output failed, falling back to JSON mode: %s", exc)
        raw_json, tokens_in, tokens_out = client.chat_json(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
        )

    # Do NOT log raw_json: it may contain residual PII that escaped the shield
    # as text echoed in sourceQuote. ADR 0012 (PII never logged).
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
