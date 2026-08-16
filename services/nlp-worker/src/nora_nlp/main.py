"""FastAPI application of the NORA NLP Worker."""

from __future__ import annotations

from fastapi import Depends, FastAPI

from .routers import analyze, health
from .security import require_internal_token

app = FastAPI(
    title="NORA NLP Worker",
    description="Internal service that analyzes transcripts via LLM (with validated JSON output).",
    version="0.1.0",
)

# The health router is deliberately NOT guarded. The compose healthcheck calls /healthz from
# inside the container without a header; gating it leaves the container permanently unhealthy
# and cloudflared, which waits on `service_healthy`, never comes up — the whole stack stays
# unreachable. Applying the dependency to the app instead of to this one router is the way to
# reproduce that.
app.include_router(health.router, tags=["health"])
app.include_router(
    analyze.router, tags=["analysis"], dependencies=[Depends(require_internal_token)]
)


def main() -> None:  # pragma: no cover
    import uvicorn

    from .settings import get_settings

    settings = get_settings()
    uvicorn.run(
        "nora_nlp.main:app",
        host="0.0.0.0",
        port=settings.worker_port,
        log_level=settings.log_level,
        reload=False,
    )


if __name__ == "__main__":  # pragma: no cover
    main()
