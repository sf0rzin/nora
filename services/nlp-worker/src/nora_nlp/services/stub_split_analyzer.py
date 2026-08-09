"""Deterministic stub of the meeting split (USE_LLM_STUB=true).

Allows developing the confirmation screen without LLM cost. Simple
heuristic: header/separator lines open a new segment. Reuses the
server-side normalization and the redacted preview from ``split_analyzer`` — the
output contract is identical to the real pipeline's.
"""

from __future__ import annotations

import re
import time
from typing import Any

from ..models import SplitRequest, SplitResponse
from .split_analyzer import assemble_segments

# Lines that indicate the start of a new meeting: separators (===, ---, ###),
# headers "Reuniao ..."/"Ata ..."/"Meeting ..." and date/time lines.
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
    # A boundary on line 1 is redundant (the first segment always starts at 1).
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
