"""Endpoint /analyze.

Recebe transcricao + contexto do tenant, aplica PII Shield e devolve uma
`MeetingAnalysisV1` validada.

Modos de operacao:
- USE_LLM_STUB=true  -> stub deterministico (sem custo, para dev)
- USE_LLM_STUB=false -> LLM real (provider agnostico, default OpenAI; ver ADR 0004)
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from ..models import AnalyzeRequest, AnalyzeResponse
from ..services import llm_analyzer, pii_shield, stub_analyzer
from ..settings import Settings, get_settings

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/analyze", response_model=AnalyzeResponse, response_model_by_alias=True)
def analyze(req: AnalyzeRequest, settings: Settings = Depends(get_settings)) -> AnalyzeResponse:
    redaction = pii_shield.redact(req.transcript)
    safe_req = req.model_copy(update={"transcript": redaction.redacted_text})

    if settings.use_llm_stub:
        return stub_analyzer.analyze(safe_req, pii_redactions_applied=len(redaction.redactions))

    try:
        return llm_analyzer.analyze(
            safe_req, settings, pii_redactions_applied=len(redaction.redactions)
        )
    except ValueError as exc:
        logger.error("Configuracao do LLM invalida: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LLM_CONFIG_INVALID",
                "message": str(exc),
            },
        ) from exc
    except Exception as exc:
        logger.exception("Erro inesperado na chamada ao LLM")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "LLM_PROVIDER_ERROR",
                "message": "Erro ao processar a transcricao. Tente novamente.",
            },
        ) from exc
