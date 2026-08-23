"""Endpoint /analyze.

Receives transcript + tenant context, applies PII Shield, computes the
interpretable TF-IDF baseline (ADR 0010) and delegates the structured analysis
to the LLM (or deterministic stub).

Operating modes:
- USE_LLM_STUB=true  -> deterministic stub (no cost, for dev)
- USE_LLM_STUB=false -> real LLM (provider agnostic, default OpenAI; see ADR 0004)

Pipeline order (sequential):
    1. PII Shield   --- already strips email/CPF/CNPJ/etc. before anything else.
    2. Baseline TF-IDF --- interpretable pre-LLM terms, over the redacted text.
    3. LLM analyze  --- generates summary/decisions/etc. with structured output.
    4. attaches baseline_terms to the response.
"""

from __future__ import annotations

import json
import logging

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import ValidationError

from ..models import (
    AnalyzeRequest,
    AnalyzeResponse,
    LiveAnalyzeRequest,
    LiveAnalyzeResponse,
    SplitRequest,
    SplitResponse,
)
from ..services import (
    baseline,
    live_analyzer,
    llm_analyzer,
    pii_shield,
    split_analyzer,
    stub_analyzer,
    stub_split_analyzer,
)
from ..settings import Settings, get_settings
from ..time_budget import LlmBudgetExceededError, TimeBudget

router = APIRouter()
logger = logging.getLogger(__name__)

# The client-facing message for a response that never parsed. Fixed text, and the same on all
# three routes: what differs between them is the schema, and there is no schema involved when
# the body is not JSON at all.
_NOT_JSON_MESSAGE = "The model returned a response that is not valid JSON."


def _schema_error_summary(exc: ValidationError) -> str:
    """Field locations and error types. NEVER the values pydantic was handed.

    `llm_analyzer` refuses to log `raw_json` in as many words -- "it may contain residual PII
    that escaped the shield as text echoed in sourceQuote" -- and three lines later
    `model_validate` raises, whose `str()` and whose `errors()` both carry `input`. For a field
    missing at the model level that input is the WHOLE response dictionary: summary,
    sourceQuote, accountName. Formatting the exception with `%s` therefore wrote into the log
    exactly what the line above had just refused to write, in an ADR-0012 repository, and the
    only thing limiting it was pydantic-core truncating long values.

    A location and a type are what a reader needs to know which field the model got wrong. The
    value is what they must not be shown. ADR 0012.
    """
    parts = [
        f"{'.'.join(str(p) for p in err['loc']) or '<root>'}: {err['type']}" for err in exc.errors()
    ]
    return "; ".join(parts) if parts else "no field detail"


def _budget_exceeded(exc: LlmBudgetExceededError, *, message: str) -> HTTPException:
    """504 for a request that ran out of wall clock before the provider answered.

    The classification is the point, as it was for the two branches above. 504 and not 500
    because nothing failed: the worker measured what was left of the caller's deadline
    (`nora.worker.timeout-millis` = 120s) and declined to start work whose answer would arrive
    after it. 504 and not 503 because the worker is available and answering.

    `warning` and not `error`: on the paid path this is the outcome that SAVES money, and a log
    level that pages someone would be describing it backwards. The exception's message carries
    step names and durations only -- never a prompt or a response. ADR 0012.
    """
    logger.warning("Gave up on the LLM inside the request budget: %s", exc)
    return HTTPException(
        status_code=status.HTTP_504_GATEWAY_TIMEOUT,
        detail={"code": "LLM_BUDGET_EXCEEDED", "message": message},
    )


def _not_json_summary(exc: json.JSONDecodeError) -> str:
    """Position and reason, without the document.

    `str(exc)` on a `JSONDecodeError` is "Expecting value: line 1 column 1 (char 0)" -- the
    coordinates of the failure and none of the text at them -- so it is safe as it stands. It is
    rebuilt from the attributes anyway rather than trusted, because "this exception's `str` is
    safe" is the assumption that made the branch above wrong.
    """
    return f"{exc.msg} at line {exc.lineno} column {exc.colno}"


@router.post("/analyze", response_model=AnalyzeResponse, response_model_by_alias=True)
def analyze(req: AnalyzeRequest, settings: Settings = Depends(get_settings)) -> AnalyzeResponse:
    # Started here rather than inside the analyzer: the caller's deadline is already running
    # during the shield and the TF-IDF baseline, and on a 1MB transcript neither is free.
    budget = TimeBudget.from_settings(settings)

    # Computed from THIS request's body and discarded when the call returns: no module cache,
    # no ContextVar, no memoisation, so one tenant's trade names cannot reach another tenant's
    # transcript by construction rather than by discipline. `redact` does not trust them --
    # it measures their effect on this text and throws the pass away if any person was freed.
    tenant_terms = pii_shield.admissible_tenant_terms(
        req.tenant_context.company_name, req.tenant_context.competitors
    )
    redaction = pii_shield.redact(req.transcript, tenant_terms)
    safe_req = req.model_copy(update={"transcript": redaction.redacted_text})

    # Baseline TF-IDF over ALREADY REDACTED text --- ensures PII does not leak
    # into the term ranking. Internal failures become an empty list (never take
    # down the request); see `services/baseline.py`.
    baseline_terms = baseline.extract_baseline_terms(redaction.redacted_text, top_n=10)

    if settings.use_llm_stub:
        response = stub_analyzer.analyze(safe_req, pii_redactions_applied=len(redaction.redactions))
        return response.model_copy(update={"baseline_terms": baseline_terms})

    try:
        response = llm_analyzer.analyze(
            safe_req, settings, pii_redactions_applied=len(redaction.redactions), budget=budget
        )
        return response.model_copy(update={"baseline_terms": baseline_terms})
    except LlmBudgetExceededError as exc:
        raise _budget_exceeded(
            exc, message="The analysis did not finish within the worker's time budget."
        ) from exc
    except ValidationError as exc:
        # MUST come before ValueError: pydantic's ValidationError IS a ValueError, so without
        # this branch a model that answers off-contract was reported as "Invalid LLM
        # configuration" — sending whoever read the log to inspect an env file that was fine.
        # It is a provider-response fault, and the message says so.
        logger.error("LLM response failed schema validation: %s", _schema_error_summary(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "LLM_RESPONSE_INVALID",
                "message": "The model returned a response outside the analysis schema.",
            },
        ) from exc
    except json.JSONDecodeError as exc:
        # AND SO MUST THIS ONE, for the same reason and one the branch above did not follow
        # through on: `json.JSONDecodeError` is also a `ValueError`, so a model that answered
        # with prose instead of JSON fell into the configuration branch below and was published
        # as 503 `LLM_CONFIG_INVALID` with the message "Expecting value: line 1 column 1
        # (char 0)" — the same wrong instruction to go and inspect an env file that is correct.
        #
        # It is the same fault as the one above, one step earlier: the provider answered off
        # contract. ADR 0004 makes the provider swappable and the OpenAI-compatible endpoints
        # people point this at (Ollama, OpenRouter) routinely ignore `response_format`, so this
        # is the ordinary failure of the configuration this repository documents, not an exotic
        # one. 502 rather than 503 because nothing here is unavailable.
        logger.error("LLM response was not JSON: %s", _not_json_summary(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "LLM_RESPONSE_INVALID", "message": _NOT_JSON_MESSAGE},
        ) from exc
    except ValueError as exc:
        logger.error("Invalid LLM configuration: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LLM_CONFIG_INVALID",
                "message": str(exc),
            },
        ) from exc
    except Exception as exc:
        logger.exception("Unexpected error calling the LLM")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "LLM_PROVIDER_ERROR",
                "message": "Error processing the transcript. Please try again.",
            },
        ) from exc


@router.post("/split", response_model=SplitResponse, response_model_by_alias=True)
def split(req: SplitRequest, settings: Settings = Depends(get_settings)) -> SplitResponse:
    """Boundary detection between meetings concatenated in a single file.

    Pipeline: PII Shield line by line → LLM (windows + strict JSON Schema) →
    server-side validation of the boundaries. Intra-line redaction ensures the
    line numbers of the redacted text match those of the original file
    (the real slicing is client-side). Nothing is persisted here.
    """
    # Before the line-by-line shield, which on a 1MB file is the most expensive non-LLM step of
    # this endpoint. Same reason as in `analyze` above.
    budget = TimeBudget.from_settings(settings)

    redacted_lines, redactions = split_analyzer.redact_lines(req.transcript)

    if settings.use_llm_stub:
        return stub_split_analyzer.analyze(req, redacted_lines, pii_redactions_applied=redactions)

    try:
        return split_analyzer.analyze(
            req, redacted_lines, settings, pii_redactions_applied=redactions, budget=budget
        )
    except LlmBudgetExceededError as exc:
        raise _budget_exceeded(
            exc, message="Meeting detection did not finish within the worker's time budget."
        ) from exc
    except (ValidationError, split_analyzer.LlmSplitShapeError) as exc:
        # `LlmSplitShapeError` is here rather than in its own branch because it says the same
        # thing a `ValidationError` says — the response parsed and is not the contract — and a
        # caller cannot act differently on the two. See its definition for the shape it covers
        # that no pydantic model was looking at.
        detail = _schema_error_summary(exc) if isinstance(exc, ValidationError) else str(exc)
        logger.error("LLM split response failed schema validation: %s", detail)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "LLM_RESPONSE_INVALID",
                "message": "The model returned a response outside the split schema.",
            },
        ) from exc
    except json.JSONDecodeError as exc:
        logger.error("LLM split response was not JSON: %s", _not_json_summary(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "LLM_RESPONSE_INVALID", "message": _NOT_JSON_MESSAGE},
        ) from exc
    except ValueError as exc:
        logger.error("Invalid LLM configuration: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LLM_CONFIG_INVALID",
                "message": str(exc),
            },
        ) from exc
    except Exception as exc:
        logger.exception("Unexpected error calling the LLM (split)")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "LLM_PROVIDER_ERROR",
                "message": "Error detecting meetings in the file. Please try again.",
            },
        ) from exc


@router.post("/analyze-live", response_model=LiveAnalyzeResponse, response_model_by_alias=True)
def analyze_live(
    req: LiveAnalyzeRequest, settings: Settings = Depends(get_settings)
) -> LiveAnalyzeResponse:
    """Real-time analysis of partial meeting chunks.

    Pipeline: PII Shield → LLM (light schema, 4 categories).
    Does not generate summary, sentiment, topics or TF-IDF baseline.
    Returns only: decisions, nextSteps, observations, tasks.
    """
    budget = TimeBudget.from_settings(settings)

    # No tenant terms, deliberately: `LiveAnalyzeRequest` carries no tenant context to compute
    # them from. This path keeps over-redacting a company name in front of a person and never
    # under-redacts a person, which is the right default for the endpoint that cannot ask.
    redaction = pii_shield.redact(req.transcript_chunk)
    safe_req = req.model_copy(update={"transcript_chunk": redaction.redacted_text})

    if settings.use_llm_stub:
        from ..services.stub_live_analyzer import analyze as stub_live

        return stub_live(safe_req, pii_redactions_applied=len(redaction.redactions))

    try:
        return live_analyzer.analyze(
            safe_req, settings, pii_redactions_applied=len(redaction.redactions), budget=budget
        )
    except LlmBudgetExceededError as exc:
        raise _budget_exceeded(
            exc, message="The live analysis did not finish within the worker's time budget."
        ) from exc
    except ValidationError as exc:
        logger.error("LLM live response failed schema validation: %s", _schema_error_summary(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "LLM_RESPONSE_INVALID",
                "message": "The model returned a response outside the live-highlights schema.",
            },
        ) from exc
    except json.JSONDecodeError as exc:
        logger.error("LLM live response was not JSON: %s", _not_json_summary(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "LLM_RESPONSE_INVALID", "message": _NOT_JSON_MESSAGE},
        ) from exc
    except ValueError as exc:
        logger.error("Invalid LLM configuration: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LLM_CONFIG_INVALID",
                "message": str(exc),
            },
        ) from exc
    except Exception as exc:
        logger.exception("Unexpected error calling the LLM (live)")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "LLM_PROVIDER_ERROR",
                "message": "Error processing live chunk. Please try again.",
            },
        ) from exc
