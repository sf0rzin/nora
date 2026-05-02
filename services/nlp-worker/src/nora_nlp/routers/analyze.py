"""Endpoint /analyze.

Recebe transcricao + contexto do tenant, aplica PII Shield e devolve uma
`MeetingAnalysisV1` validada. No MVP usa stub deterministico (USE_LLM_STUB=true).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from ..models import AnalyzeRequest, AnalyzeResponse
from ..services import pii_shield, stub_analyzer
from ..settings import Settings, get_settings

router = APIRouter()


@router.post("/analyze", response_model=AnalyzeResponse, response_model_by_alias=True)
def analyze(req: AnalyzeRequest, settings: Settings = Depends(get_settings)) -> AnalyzeResponse:
    redaction = pii_shield.redact(req.transcript)
    safe_req = req.model_copy(update={"transcript": redaction.redacted_text})

    if settings.use_llm_stub:
        return stub_analyzer.analyze(safe_req, pii_redactions_applied=len(redaction.redactions))

    # Implementacao real (Azure OpenAI) entra em historia futura do backlog.
    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail={
            "code": "LLM_PROVIDER_NOT_CONFIGURED",
            "message": "USE_LLM_STUB=false mas o provider LLM real ainda nao foi implementado.",
        },
    )
