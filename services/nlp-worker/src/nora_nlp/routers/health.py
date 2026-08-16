"""Health endpoints.

`/healthz` (liveness): shallow. Returns 200 if the process is alive.
`/readyz`  (readiness): validates minimal configuration (LLM_API_KEY when
USE_LLM_STUB=false) and reports whether internal auth is enforced.

Neither is behind `require_internal_token`: the compose healthcheck calls `/healthz` from
inside the container with no header, and a gated `/healthz` would keep the container
unhealthy forever — which stops `cloudflared` (`condition: service_healthy`) from starting
and takes the whole stack offline.
"""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status

from ..security import internal_auth_state
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
            detail={"code": "NOT_READY", "reason": "LLM_API_KEY missing with USE_LLM_STUB=false"},
        )
    return {
        "service": "nora-nlp-worker",
        "status": "ready",
        "stubMode": "true" if settings.use_llm_stub else "false",
        # "off" means the analysis routes are answering 503 (no token configured) or
        # accepting anyone (the explicit dev opt-out). Reported here so the state of the gate
        # is observable without reading the container's environment.
        "internalAuth": internal_auth_state(settings),
        "timestamp": datetime.now(UTC).isoformat(),
    }
