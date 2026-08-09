"""Split Analyzer: boundary detection between meetings in a single file.

The user uploads ONE .txt file with several concatenated meetings; the worker
proposes the cut points (1-based lines) and the real slicing happens
client-side over the ORIGINAL file. That is why the line numbers of the
redacted text must match 1:1 those of the original — see ``redact_lines``.

Pipeline:
  1. PII Shield LINE BY LINE (``redact_lines``) — intra-line redaction.
  2. Numbers the lines (1-based, format ``N| text``) and builds windows.
  3. LLM (gpt-4o-mini via the agnostic client, ADR 0004) with strict JSON Schema
     (ADR 0003) per window; fallback to JSON mode.
  4. Merges the boundaries of the windows (strategy documented in ``analyze``).
  5. Server-side normalization (``normalize_segments``) — never trusts the LLM.
  6. Redacted previews (~200 chars) + execution metadata.

Window + merge strategy (transcript larger than the context budget):
  - Each window covers consecutive lines up to ``_WINDOW_CHAR_BUDGET`` chars
    (~60k tokens — ample slack within the 128k of gpt-4o-mini, discounting
    prompt and output).
  - If the window detected >= 2 segments, the LAST one is probably truncated
    by the window limit: it is discarded and the next window STARTS at its
    ``startLine`` — that is, the truncated stretch is re-analyzed in full
    (this is the overlap between windows; the accepted boundaries are always
    those of the window that saw the complete segment).
  - If the window detected only 1 segment (no internal boundary), the meeting
    probably continues in the next window: the segment stays "pending" and is
    merged with the first segment of the following window (the title of the
    window that saw the START of the meeting wins; confidence becomes the
    minimum of the two).
  - Guaranteed progress: the next window always starts after the beginning of
    the current one; in the degenerate case it falls back to ``window end + 1``.

Pragmatic cap: files up to 1MB (same limit as /analyze).
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any

from ..clients.llm import LlmClient
from ..models import SplitRequest, SplitResponse, SplitSegment
from ..settings import Settings
from .pii_shield import redact as pii_redact
from .prompt_utils import load_prompt, render_template

logger = logging.getLogger(__name__)

PROMPT_VERSION = "meeting-split-v1"

# Char budget per window (~60k tokens at ~4 chars/token). 1MB of transcript
# becomes at most ~5 windows.
_WINDOW_CHAR_BUDGET = 240_000

# Size of the redacted preview returned per segment.
_PREVIEW_MAX_CHARS = 200

_TITLE_MAX_CHARS = 120

_FALLBACK_TITLE = "Transcricao completa"

_WHITESPACE_RE = re.compile(r"\s+")


# --------------------------------------------------------------------------- #
# PII Shield line by line
# --------------------------------------------------------------------------- #


def redact_lines(transcript: str) -> tuple[list[str], int]:
    """Applies the PII Shield line by line and returns (redacted lines, count).

    The global shield (``pii_shield.redact`` over the whole text) can match
    patterns CROSSING ``\\n`` (phone/CPF with ``\\s`` in the regex, compound
    names broken across two lines), which would remove line breaks and
    break the 1:1 mapping between lines of the original and of the redacted text.

    Redacting line by line guarantees intra-line redaction: each placeholder
    replaces text inside the SAME line, so ``startLine``/``endLine``
    computed over the redacted text hold for the original file — a requirement
    of the client-side slicing. Cost: the placeholder numbering restarts on
    every line (irrelevant here: the preview needs no global dedup).
    """
    # Normalizes line breaks BEFORE numbering: CRLF (Windows) and CR (classic
    # Mac) become LF. The client-side slicing (sliceFileLines in
    # apps/web/.../upload/page.tsx) does exactly the same normalization before
    # cutting by startLine/endLine; without it, a file with a loose CR would have
    # a line count diverging between worker and front end and the cuts would come
    # out shifted. (CRLF already matched in the count, but left a residual \r in
    # the preview — removed here too.)
    normalized = transcript.replace("\r\n", "\n").replace("\r", "\n")
    lines = normalized.split("\n")
    redacted: list[str] = []
    count = 0
    for line in lines:
        out = pii_redact(line)
        redacted.append(out.redacted_text)
        count += len(out.redactions)
    return redacted, count


# --------------------------------------------------------------------------- #
# Prompt helpers (load_prompt / render_template in services/prompt_utils.py)
# --------------------------------------------------------------------------- #


def _build_json_schema_for_split() -> dict[str, Any]:
    """Strict JSON Schema of the LLM output (boundaries only; preview/index are
    computed server-side over the redacted text)."""
    return {
        "type": "object",
        "properties": {
            "segments": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "startLine": {"type": "integer"},
                        "endLine": {"type": "integer"},
                        "confidence": {"type": "number"},
                    },
                    "required": ["title", "startLine", "endLine", "confidence"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["segments"],
        "additionalProperties": False,
    }


# --------------------------------------------------------------------------- #
# Server-side validation (do not trust the LLM)
# --------------------------------------------------------------------------- #


def _clamp_window_segments(
    raw: list[dict[str, Any]], window_start: int, window_end: int
) -> list[dict[str, Any]]:
    """Sanitizes segments of ONE window: valid ints, clamped to the window
    range, sorted by startLine. Garbage (start > end after clamp) is discarded.
    """
    cleaned: list[dict[str, Any]] = []
    for seg in raw:
        try:
            start = int(seg.get("startLine"))
            end = int(seg.get("endLine"))
        except (TypeError, ValueError):
            continue
        start = min(max(start, window_start), window_end)
        end = min(max(end, start), window_end)
        try:
            confidence = float(seg.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        confidence = min(max(confidence, 0.0), 1.0)
        title = str(seg.get("title") or "").strip()[:_TITLE_MAX_CHARS]
        cleaned.append(
            {"title": title, "startLine": start, "endLine": end, "confidence": confidence}
        )
    cleaned.sort(key=lambda s: (s["startLine"], s["endLine"]))
    return cleaned


def normalize_segments(raw: list[dict[str, Any]], total_lines: int) -> list[dict[str, Any]]:
    """Normalizes the global list of segments to the endpoint contract:

    - sorted by ``startLine``;
    - without overlap (overlap is cut: the next one starts after the
      end of the previous; a segment swallowed whole is discarded);
    - within ``[1, total_lines]``;
    - covering the whole file: holes become an extension of the PREVIOUS segment
      (and the first segment is extended to line 1; the last one to the end);
    - an empty list becomes 1 single segment covering the file (fallback).

    Only 1 segment is also a valid answer (single-meeting file).
    """
    cleaned: list[dict[str, Any]] = []
    for seg in raw:
        try:
            start = int(seg.get("startLine"))
            end = int(seg.get("endLine"))
        except (TypeError, ValueError):
            continue
        start = min(max(start, 1), total_lines)
        end = min(max(end, start), total_lines)
        try:
            confidence = float(seg.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        cleaned.append(
            {
                "title": str(seg.get("title") or "").strip()[:_TITLE_MAX_CHARS],
                "startLine": start,
                "endLine": end,
                "confidence": min(max(confidence, 0.0), 1.0),
            }
        )

    cleaned.sort(key=lambda s: (s["startLine"], s["endLine"]))

    result: list[dict[str, Any]] = []
    for seg in cleaned:
        if not result:
            seg["startLine"] = 1  # head of the file always covered
            result.append(seg)
            continue
        prev = result[-1]
        if seg["startLine"] <= prev["endLine"]:
            seg["startLine"] = prev["endLine"] + 1
            if seg["startLine"] > seg["endLine"]:
                continue  # swallowed whole by the previous one
        elif seg["startLine"] > prev["endLine"] + 1:
            prev["endLine"] = seg["startLine"] - 1  # hole becomes extension of the previous
        result.append(seg)

    if not result:
        result = [
            {
                "title": _FALLBACK_TITLE,
                "startLine": 1,
                "endLine": total_lines,
                "confidence": 0.0,
            }
        ]

    result[-1]["endLine"] = total_lines  # tail of the file always covered
    return result


def build_preview(redacted_lines: list[str], start_line: int, end_line: int) -> str:
    """First ~200 chars of the ALREADY REDACTED segment, on a single line."""
    text = "\n".join(redacted_lines[start_line - 1 : end_line]).strip()
    text = _WHITESPACE_RE.sub(" ", text)
    return text[:_PREVIEW_MAX_CHARS]


def assemble_segments(
    raw_segments: list[dict[str, Any]],
    redacted_lines: list[str],
) -> list[SplitSegment]:
    """Normalizes + materializes the final ``SplitSegment``s (1-based index,
    redacted preview, title with fallback)."""
    total_lines = len(redacted_lines)
    normalized = normalize_segments(raw_segments, total_lines)
    segments: list[SplitSegment] = []
    for i, seg in enumerate(normalized, start=1):
        title = seg["title"] or f"Reuniao {i}"
        preview = build_preview(redacted_lines, seg["startLine"], seg["endLine"])
        segments.append(
            SplitSegment(
                index=i,
                title=title,
                startLine=seg["startLine"],
                endLine=seg["endLine"],
                confidence=seg["confidence"],
                preview=preview,
            )
        )
    return segments


# --------------------------------------------------------------------------- #
# Main pipeline
# --------------------------------------------------------------------------- #


def _window_end(numbered: list[str], start_line: int, char_budget: int) -> int:
    """Last line (1-based, inclusive) of the window starting at ``start_line``.

    Guarantees at least 1 line per window even if the line alone blows the
    budget (a pathologically long line does not stall the loop).
    """
    consumed = 0
    end = start_line - 1  # no line consumed yet
    for i in range(start_line - 1, len(numbered)):
        line_cost = len(numbered[i]) + 1  # +1 for the \n
        if consumed + line_cost > char_budget and end >= start_line:
            break
        consumed += line_cost
        end = i + 1
    return end


def analyze(
    req: SplitRequest,
    redacted_lines: list[str],
    settings: Settings,
    *,
    pii_redactions_applied: int = 0,
) -> SplitResponse:
    """Detects meeting boundaries via LLM, in windows if needed."""
    started = time.monotonic()

    client = LlmClient(settings)
    system_prompt, user_template = load_prompt(PROMPT_VERSION)
    json_schema = _build_json_schema_for_split()

    total_lines = len(redacted_lines)
    numbered = [f"{i + 1}| {line}" for i, line in enumerate(redacted_lines)]

    raw_segments: list[dict[str, Any]] = []
    pending: dict[str, Any] | None = None  # open segment from the previous window
    tokens_in_total = 0
    tokens_out_total = 0

    pos = 1
    while pos <= total_lines:
        end = _window_end(numbered, pos, _WINDOW_CHAR_BUDGET)
        window_text = "\n".join(numbered[pos - 1 : end])
        user_prompt = render_template(
            user_template,
            language=req.language,
            first_line=str(pos),
            last_line=str(end),
            numbered_transcript=window_text,
        )

        try:
            raw_json, tokens_in, tokens_out = client.chat_structured(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                json_schema=json_schema,
                schema_name="meeting_split",
                temperature=0.1,
            )
        except Exception as exc:
            logger.warning("Structured output falhou no split, fallback JSON mode: %s", exc)
            raw_json, tokens_in, tokens_out = client.chat_json(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                temperature=0.1,
            )
        tokens_in_total += tokens_in
        tokens_out_total += tokens_out

        # Do NOT log raw_json: ADR 0012 (PII never logged).
        logger.debug("Split LLM raw response: %d chars (janela %d-%d)", len(raw_json), pos, end)

        parsed = json.loads(raw_json)
        win = _clamp_window_segments(parsed.get("segments") or [], pos, end)

        # Merges the pending segment (previous window without an internal
        # boundary) with the first of this window: same meeting continuing.
        if pending is not None:
            if win:
                first = win[0]
                first["startLine"] = pending["startLine"]
                first["title"] = pending["title"] or first["title"]
                first["confidence"] = min(pending["confidence"], first["confidence"])
            else:
                raw_segments.append(pending)
            pending = None

        if end >= total_lines:
            raw_segments.extend(win)
            break

        if len(win) >= 2:
            # Last segment probably truncated by the window: re-analyzes it
            # in full in the next window (controlled overlap).
            tail = win[-1]
            raw_segments.extend(win[:-1])
            pos = tail["startLine"] if tail["startLine"] > pos else end + 1
        elif len(win) == 1:
            pending = win[0]
            pos = end + 1
        else:
            # LLM returned nothing useful: treats the window as a single meeting
            # without a title (normalize/fallback handle the rest).
            pending = {"title": "", "startLine": pos, "endLine": end, "confidence": 0.0}
            pos = end + 1

    if pending is not None:
        raw_segments.append(pending)

    segments = assemble_segments(raw_segments, redacted_lines)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    return SplitResponse.model_validate(
        {
            "segments": [s.model_dump(by_alias=True) for s in segments],
            "totalLines": total_lines,
            "metadata": {
                "modelVersion": f"{settings.llm_provider}-{settings.llm_model}",
                "promptVersion": PROMPT_VERSION,
                "tokensInput": tokens_in_total,
                "tokensOutput": tokens_out_total,
                "processingMillis": elapsed_ms,
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )
