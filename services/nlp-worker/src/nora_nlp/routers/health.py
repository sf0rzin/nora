"""Endpoints de saude.

`/healthz` (liveness): raso. Retorna 200 se o processo esta vivo.
`/readyz`  (readiness): valida configuracao minima (LLM_API_KEY quando
USE_LLM_STUB=false). Container Apps deveria usar `/readyz` como readinessProbe
e `/healthz` como livenessProbe.
"""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status

from ..settings import Settings, get_settings

router = APIRouter()


@router.get("/healthz")
def healthz() -> dict[str, str]:
    return {
        "service": "nora-nlp-worker",
        "status": "ok",
        "timestamp": datetime.now(UTC).isoformat(),
    }


@router.get("/readyz")
def readyz(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    """Pronto para servir trafego. Falha 503 se config nao satisfaz o modo escolhido."""
    if not settings.use_llm_stub and not settings.llm_api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "NOT_READY", "reason": "LLM_API_KEY ausente com USE_LLM_STUB=false"},
        )
    return {
        "service": "nora-nlp-worker",
        "status": "ready",
        "stubMode": "true" if settings.use_llm_stub else "false",
        "timestamp": datetime.now(UTC).isoformat(),
    }
