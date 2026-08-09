"""Health endpoints.

`/healthz` (liveness): shallow. Returns 200 if the process is alive.
`/readyz`  (readiness): validates minimal configuration (LLM_API_KEY when
USE_LLM_STUB=false). Container Apps should use `/readyz` as readinessProbe
and `/healthz` as livenessProbe.
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
    """Ready to serve traffic. Fails 503 if config does not satisfy the chosen mode."""
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
