"""Service-to-service authentication for the worker's analysis routes.

Until this existed, anything that could open a socket to ``worker:8001`` could POST a
transcript to ``/analyze`` and spend an LLM call on the project's account — every container
on the compose ``internal``/``edge`` bridges, and anything on the machine in dev. ADR 0033
already argued from the premise that "the worker already has the shield and the internal
token"; the shield was real, the token was not.

The shape mirrors the backend's ``InternalTokenAuthFilter`` (ADR 0023 §3-4) in the opposite
direction: the API calls the worker, so the API sends ``X-Internal-Token`` and the worker
verifies it. It is a DIFFERENT secret from ``NORA_PLATFORM_INTERNAL_TOKEN``, which covers
worker/BFF -> API.

Three states, and the middle one is the point:

===========================  ====================================================
configured, header matches   pass (``hmac.compare_digest``, no early exit on the
                             first differing byte)
configured, header wrong     401 ``UNAUTHORIZED``
not configured               503 ``INTERNAL_AUTH_NOT_CONFIGURED`` — fail-closed,
                             unless ``NORA_WORKER_ALLOW_UNAUTHENTICATED=true``
===========================  ====================================================

Only the analysis router depends on this. ``/healthz`` and ``/readyz`` stay open on purpose:
the compose healthcheck calls ``/healthz`` from inside the container with no header, and
gating it would leave the container permanently unhealthy — which takes the whole stack
down, since ``cloudflared`` waits on ``service_healthy``.
"""

from __future__ import annotations

import hmac
import logging

from fastapi import Depends, Header, HTTPException, status

from .settings import Settings, get_settings

logger = logging.getLogger(__name__)

HEADER_NAME = "X-Internal-Token"

_UNAUTHENTICATED_WARNING = (
    "NORA_WORKER_INTERNAL_TOKEN is empty and NORA_WORKER_ALLOW_UNAUTHENTICATED=true — the "
    "analysis routes accept any caller that can reach this port. Acceptable in local dev only."
)


def internal_auth_state(settings: Settings) -> str:
    """``"on"`` when a token is enforced, ``"off"`` when the opt-out is in effect.

    Exposed so ``/readyz`` can report it: the state of an auth gate should be readable
    without shell access to the container's environment.
    """
    if settings.worker_internal_token:
        return "on"
    return "off"


def require_internal_token(
    x_internal_token: str = Header(default=""),
    settings: Settings = Depends(get_settings),
) -> None:
    """FastAPI dependency guarding the analysis routes. Raises, or returns ``None``."""
    expected = settings.worker_internal_token
    if not expected:
        if settings.allow_unauthenticated_internal:
            logger.warning(_UNAUTHENTICATED_WARNING)
            return None
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "INTERNAL_AUTH_NOT_CONFIGURED",
                "reason": (
                    "NORA_WORKER_INTERNAL_TOKEN is not set. Set it (and send it in the "
                    "X-Internal-Token header), or set NORA_WORKER_ALLOW_UNAUTHENTICATED=true "
                    "for local development."
                ),
            },
        )

    # compare_digest over bytes: the str overload rejects non-ASCII, and the header is
    # attacker-controlled.
    if not hmac.compare_digest(x_internal_token.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "UNAUTHORIZED", "reason": f"missing or invalid {HEADER_NAME}"},
        )
    return None
