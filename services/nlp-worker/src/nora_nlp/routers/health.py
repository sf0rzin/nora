"""Endpoint de saude."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter

router = APIRouter()


@router.get("/healthz")
def healthz() -> dict[str, str]:
    return {
        "service": "nora-nlp-worker",
        "status": "ok",
        "timestamp": datetime.now(UTC).isoformat(),
    }
