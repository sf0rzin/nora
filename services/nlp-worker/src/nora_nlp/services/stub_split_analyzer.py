"""Stub deterministico do split de reunioes (USE_LLM_STUB=true).

Permite desenvolver a tela de confirmacao sem custo de LLM. Heuristica
simples: linhas de cabecalho/separador abrem um novo segmento. Reusa a
normalizacao server-side e o preview redigido do ``split_analyzer`` — o
contrato de saida e identico ao do pipeline real.
"""

from __future__ import annotations

import re
import time
from typing import Any

from ..models import SplitRequest, SplitResponse
from .split_analyzer import assemble_segments

# Linhas que indicam comeco de uma nova reuniao: separadores (===, ---, ###),
# cabecalhos "Reuniao ..."/"Ata ..."/"Meeting ..." e linhas de data/hora.
_BOUNDARY_RE = re.compile(
    r"^\s*(?:"
    r"={3,}.*"
    r"|-{3,}\s*$"
    r"|#{1,3}\s+.+"
    r"|\[?\s*(?:reuni[aã]o|ata|meeting)\b.*"
    r"|data\s*:.*"
    r"|\d{1,2}/\d{1,2}/\d{2,4}.*"
    r")\s*$",
    re.IGNORECASE,
)

_TITLE_TRIM_RE = re.compile(r"^[\s=\-#\[\]>*]+|[\s=\-#\[\]>*]+$")


def _title_from_boundary(line: str, ordinal: int) -> str:
    cleaned = _TITLE_TRIM_RE.sub("", line).strip()
    return cleaned[:120] if cleaned else f"Reuniao {ordinal}"


def analyze(
    req: SplitRequest,
    redacted_lines: list[str],
    *,
    pii_redactions_applied: int = 0,
) -> SplitResponse:
    started = time.monotonic()
    total_lines = len(redacted_lines)

    boundaries = [i + 1 for i, line in enumerate(redacted_lines) if _BOUNDARY_RE.match(line)]
    # Fronteira na linha 1 e redundante (primeiro segmento sempre comeca em 1).
    boundaries = [b for b in boundaries if b > 1]

    raw_segments: list[dict[str, Any]] = []
    starts = [1, *boundaries]
    for n, start in enumerate(starts):
        end = (starts[n + 1] - 1) if n + 1 < len(starts) else total_lines
        if end < start:
            continue
        boundary_line = redacted_lines[start - 1] if start > 1 else redacted_lines[0]
        raw_segments.append(
            {
                "title": _title_from_boundary(boundary_line, n + 1),
                "startLine": start,
                "endLine": end,
                "confidence": 0.9 if start > 1 else 0.6,
            }
        )

    segments = assemble_segments(raw_segments, redacted_lines)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    return SplitResponse.model_validate(
        {
            "segments": [s.model_dump(by_alias=True) for s in segments],
            "totalLines": total_lines,
            "metadata": {
                "modelVersion": "stub-split-v1",
                "promptVersion": "meeting-split-v1",
                "tokensInput": 0,
                "tokensOutput": 0,
                "processingMillis": elapsed_ms,
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )
