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

import logging

from fastapi import APIRouter, Depends, HTTPException, status

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

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/analyze", response_model=AnalyzeResponse, response_model_by_alias=True)
def analyze(req: AnalyzeRequest, settings: Settings = Depends(get_settings)) -> AnalyzeResponse:
    redaction = pii_shield.redact(req.transcript)
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
            safe_req, settings, pii_redactions_applied=len(redaction.redactions)
        )
        return response.model_copy(update={"baseline_terms": baseline_terms})
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
    redacted_lines, redactions = split_analyzer.redact_lines(req.transcript)

    if settings.use_llm_stub:
        return stub_split_analyzer.analyze(req, redacted_lines, pii_redactions_applied=redactions)

    try:
        return split_analyzer.analyze(
            req, redacted_lines, settings, pii_redactions_applied=redactions
        )
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
    redaction = pii_shield.redact(req.transcript_chunk)
    safe_req = req.model_copy(update={"transcript_chunk": redaction.redacted_text})

    if settings.use_llm_stub:
        from ..services.stub_live_analyzer import analyze as stub_live

        return stub_live(safe_req, pii_redactions_applied=len(redaction.redactions))

    try:
        return live_analyzer.analyze(
            safe_req, settings, pii_redactions_applied=len(redaction.redactions)
        )
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
