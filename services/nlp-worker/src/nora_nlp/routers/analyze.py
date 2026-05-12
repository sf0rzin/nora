"""Endpoint /analyze.

Recebe transcricao + contexto do tenant, aplica PII Shield, calcula baseline
TF-IDF interpretavel (ADR 0010) e delega a analise estruturada para o LLM
(ou stub deterministico).

Modos de operacao:
- USE_LLM_STUB=true  -> stub deterministico (sem custo, para dev)
- USE_LLM_STUB=false -> LLM real (provider agnostico, default OpenAI; ver ADR 0004)

Ordem do pipeline (sequencial):
    1. PII Shield   --- ja remove email/CPF/CNPJ/etc. antes de tudo.
    2. Baseline TF-IDF --- termos interpretaveis pre-LLM, sobre o texto redigido.
    3. LLM analyze  --- gera summary/decisions/etc. com structured output.
    4. anexa baseline_terms ao response.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from ..models import AnalyzeRequest, AnalyzeResponse, LiveAnalyzeRequest, LiveAnalyzeResponse
from ..services import baseline, live_analyzer, llm_analyzer, pii_shield, stub_analyzer
from ..settings import Settings, get_settings

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/analyze", response_model=AnalyzeResponse, response_model_by_alias=True)
def analyze(req: AnalyzeRequest, settings: Settings = Depends(get_settings)) -> AnalyzeResponse:
    redaction = pii_shield.redact(req.transcript)
    safe_req = req.model_copy(update={"transcript": redaction.redacted_text})

    # Baseline TF-IDF sobre texto JA REDIGIDO --- garante que PII nao vaza pro
    # ranking de termos. Falhas internas viram lista vazia (nunca derrubam a
    # request); ver `services/baseline.py`.
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


@router.post("/analyze-live", response_model=LiveAnalyzeResponse, response_model_by_alias=True)
def analyze_live(
    req: LiveAnalyzeRequest, settings: Settings = Depends(get_settings)
) -> LiveAnalyzeResponse:
    """Analise em tempo real de trechos parciais de reunioes.

    Pipeline: PII Shield → LLM (schema leve, 4 categorias).
    Nao gera summary, sentiment, topics nem baseline TF-IDF.
    Retorna apenas: decisions, nextSteps, observations, tasks.
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
        logger.error("Configuracao do LLM invalida: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LLM_CONFIG_INVALID",
                "message": str(exc),
            },
        ) from exc
    except Exception as exc:
        logger.exception("Erro inesperado na chamada ao LLM (live)")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "LLM_PROVIDER_ERROR",
                "message": "Erro ao processar trecho ao vivo. Tente novamente.",
            },
        ) from exc
