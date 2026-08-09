"""FastAPI application of the NORA NLP Worker."""

from __future__ import annotations

from fastapi import FastAPI

from .routers import analyze, health

app = FastAPI(
    title="NORA NLP Worker",
    description="Servico interno de analise de transcricoes via LLM (com saida JSON validada).",
    version="0.1.0",
)

app.include_router(health.router, tags=["health"])
app.include_router(analyze.router, tags=["analysis"])


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
