"""Stub deterministico do analisador em tempo real (live).

Permite desenvolver a overlay sem custo de LLM. Aplica heuristicas simples
para extrair decisoes, proximos passos, observacoes e tarefas de trechos
parciais de reuniao.
"""

from __future__ import annotations

import re
import time

from ..models import (
    LiveAnalyzeRequest,
    LiveAnalyzeResponse,
    LiveHighlightItem,
    LiveTaskItem,
    Priority,
)

_DECISION_HINTS = {"decidido", "fechado", "combinado", "vamos com", "decisao", "decidimos"}
_NEXT_STEP_HINTS = {"precisamos", "vamos verificar", "precisamos avaliar", "checar", "investigar"}
_OBSERVATION_HINTS = {"interessante", "notar", "atencao", "cuidado", "importante", "relevante"}
_TASK_PATTERNS = (
    re.compile(r"(?:vou|vamos|precisa|tem que)\s+(.{10,80})", re.IGNORECASE),
    re.compile(r"(?:enviar|preparar|agendar|marcar|criar|montar)\s+(.{10,80})", re.IGNORECASE),
)


def _normalize(s: str) -> str:
    return (
        s.lower()
        .replace("\u00e3", "a")
        .replace("\u00e1", "a")
        .replace("\u00e2", "a")
        .replace("\u00e9", "e")
        .replace("\u00ea", "e")
        .replace("\u00ed", "i")
        .replace("\u00f3", "o")
        .replace("\u00f4", "o")
        .replace("\u00f5", "o")
        .replace("\u00fa", "u")
        .replace("\u00e7", "c")
    )


def _split_sentences(text: str) -> list[str]:
    lines = [line.strip() for line in re.split(r"\r?\n", text) if line.strip()]
    sentences: list[str] = []
    for line in lines:
        stripped = re.sub(r"^\[[^\]]+\]\s*", "", line)
        speaker_prefix = (
            r"^[A-Z]"
            r"[\w\u00e1\u00e9\u00ed\u00f3\u00fa\u00e3\u00e2\u00ea\u00f4\u00e7 ]{0,40}"
            r":\s*"
        )
        stripped = re.sub(speaker_prefix, "", stripped)
        sentences.extend(p.strip() for p in re.split(r"(?<=[.!?])\s+", stripped) if p.strip())
    return sentences


def _is_duplicate(text: str, existing: list[LiveHighlightItem]) -> bool:
    norm_new = _normalize(text)
    new_tokens = set(norm_new.split())
    for item in existing:
        norm_old = _normalize(item.text)
        if norm_new == norm_old:
            return True
        if norm_new in norm_old or norm_old in norm_new:
            return True
        old_tokens = set(norm_old.split())
        if new_tokens and old_tokens:
            overlap = len(new_tokens & old_tokens) / min(len(new_tokens), len(old_tokens))
            if overlap >= 0.7:
                return True
    return False


def analyze(req: LiveAnalyzeRequest, *, pii_redactions_applied: int = 0) -> LiveAnalyzeResponse:
    started = time.monotonic()
    sentences = _split_sentences(req.transcript_chunk)

    existing_decisions = req.previous_highlights.decisions if req.previous_highlights else []
    existing_next = req.previous_highlights.next_steps if req.previous_highlights else []
    existing_obs = req.previous_highlights.observations if req.previous_highlights else []

    decisions: list[LiveHighlightItem] = []
    next_steps: list[LiveHighlightItem] = []
    observations: list[LiveHighlightItem] = []
    tasks: list[LiveTaskItem] = []

    for s in sentences:
        n = _normalize(s)
        if not s or len(s) < 5:
            continue

        if any(h in n for h in _DECISION_HINTS) and not _is_duplicate(
            s, existing_decisions + decisions
        ):
            confidence = 0.85 if ("data" in n or "ate " in n) else 0.7
            decisions.append(
                LiveHighlightItem(
                    text=s[:500], confidence=confidence, sourceQuote=s[:500]
                )
            )

        if any(h in n for h in _NEXT_STEP_HINTS) and not _is_duplicate(
            s, existing_next + next_steps
        ):
            next_steps.append(
                LiveHighlightItem(
                    text=s[:500], confidence=0.65, sourceQuote=s[:500]
                )
            )

        if any(h in n for h in _OBSERVATION_HINTS) and not _is_duplicate(
            s, existing_obs + observations
        ):
            observations.append(
                LiveHighlightItem(
                    text=s[:500], confidence=0.6, sourceQuote=s[:500]
                )
            )

        for pat in _TASK_PATTERNS:
            m = pat.search(s)
            if m:
                title = m.group(1).strip()[:240]
                is_urgent = "ate " in n or "hoje" in n or "amanha" in n
                priority = Priority.HIGH if is_urgent else Priority.MEDIUM
                tasks.append(
                    LiveTaskItem(
                        title=title,
                        assignee=None,
                        priority=priority,
                        sourceQuote=s[:500],
                    )
                )
                break

    elapsed_ms = int((time.monotonic() - started) * 1000)

    return LiveAnalyzeResponse.model_validate(
        {
            "decisions": decisions[:20],
            "nextSteps": next_steps[:20],
            "observations": observations[:20],
            "tasks": tasks[:30],
            "metadata": {
                "processingMillis": elapsed_ms,
                "tokensInput": 0,
                "tokensOutput": 0,
                "piiRedactionsApplied": pii_redactions_applied,
                "modelVersion": "stub-live-v1",
            },
        }
    )
