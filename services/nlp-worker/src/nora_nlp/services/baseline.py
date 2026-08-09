"""Baseline TF-IDF step of the NLP pipeline.

Decision: the TF-IDF computation lives in the ``nlp_baseline`` package (pure
sklearn, no NLTK deps at runtime). This module is only the adapter between the
worker pipeline and ``TfidfBaseline``: chunks the transcript into
pseudo-documents, runs the fit, returns the top terms in the worker's
``BaselineTerm`` format.

See ADR 0010 for the shared-package decision.

Position in the pipeline (router /analyze):
    1. PII Shield redaction   <-- already applied when this module is called.
    2. *extract_baseline_terms(safe_transcript)*  <-- here.
    3. (parallel) LLM analyze.
    4. attaches baseline_terms to the response.

The text arriving here is already ``safe_transcript`` (``[[EMAIL_1]]``
placeholders replaced PII). Since the internal TF-IDF normalizes accents and
punctuation, the placeholders become odd tokens (e.g.: ``email``) and end up
filtered out or made unimportant by the IDF --- acceptable for a baseline.
"""

from __future__ import annotations

import logging
import re

from nlp_baseline import TfidfBaseline

from ..models import BaselineTerm

logger = logging.getLogger(__name__)

# Minimum chunk size in characters. Below that the IDF does not discriminate.
_MIN_CHUNK_CHARS = 80
# Target number of chunks --- more than that tends to over-fragment a short
# transcript and produce noise; fewer kills the multi-segment signal.
_TARGET_CHUNKS = 6


def _chunk_transcript(transcript: str) -> list[str]:
    """Splits transcript into pseudo-documents for the TF-IDF.

    Strategy:
    * Tries to split by speaking turns (lines with a speaker prefix).
    * If the result has few turns (< 2), falls back to a paragraph split.
    * If still too short, returns the whole transcript as 1 doc.

    Reason: TF-IDF with a single document works (TfidfBaseline handles the
    degenerate case), but loses discrimination. With 3-6 chunks the IDF starts
    to make sense --- it penalizes terms that appear in every turn.
    """
    if not transcript or not transcript.strip():
        return []

    # 1) Turns --- lines matching ``Name: ...`` or ``[timestamp] Name: ...``
    speaker_re = re.compile(r"^(?:\[[^\]]+\]\s*)?[A-Z][\wáéíóúãâêôç ]{0,40}:\s*", re.MULTILINE)
    turns_by_speaker: list[str] = []
    matches = list(speaker_re.finditer(transcript))
    if len(matches) >= 2:
        for i, m in enumerate(matches):
            start = m.end()
            end = matches[i + 1].start() if i + 1 < len(matches) else len(transcript)
            turn = transcript[start:end].strip()
            if turn:
                turns_by_speaker.append(turn)
        # If we got enough turns, we return them.
        if len(turns_by_speaker) >= 2:
            # Caps at _TARGET_CHUNKS by grouping small turns.
            return _coalesce(turns_by_speaker, target=_TARGET_CHUNKS)

    # 2) Paragraphs --- lines separated by double whitespace
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", transcript) if p.strip()]
    if len(paragraphs) >= 2:
        return _coalesce(paragraphs, target=_TARGET_CHUNKS)

    # 3) Lines --- last fallback before dropping to a single doc
    lines = [line.strip() for line in transcript.splitlines() if line.strip()]
    if len(lines) >= 4:
        return _coalesce(lines, target=_TARGET_CHUNKS)

    # 4) Single doc
    return [transcript.strip()]


def _coalesce(parts: list[str], *, target: int) -> list[str]:
    """Joins adjacent parts until there are at most ``target`` chunks.

    Ensures chunks are at least ``_MIN_CHUNK_CHARS`` long when possible.
    """
    if len(parts) <= target:
        # Even so, ensures a minimum size per chunk.
        out: list[str] = []
        for p in parts:
            if out and len(out[-1]) < _MIN_CHUNK_CHARS:
                out[-1] = f"{out[-1]} {p}".strip()
            else:
                out.append(p)
        return out

    # Coalesce into uniform windows.
    bucket_size = max(1, (len(parts) + target - 1) // target)
    grouped: list[str] = []
    for i in range(0, len(parts), bucket_size):
        chunk = " ".join(parts[i : i + bucket_size]).strip()
        if chunk:
            grouped.append(chunk)
    return grouped


def extract_baseline_terms(transcript: str, *, top_n: int = 10) -> list[BaselineTerm]:
    """Extracts the ``top_n`` most relevant TF-IDF terms from the transcript.

    Args:
        transcript: already redacted transcript (or raw, if PII does not apply).
        top_n: maximum number of terms returned.

    Returns:
        List of ``BaselineTerm`` sorted by score desc. Empty list on an empty
        transcript, or if the TF-IDF could not get a useful vocabulary
        (extremely short transcript, only stopwords, etc.). Never raises
        --- the baseline is an optional *step* and can never take down the request.
    """
    if not transcript or not transcript.strip():
        return []
    if top_n <= 0:
        return []

    chunks = _chunk_transcript(transcript)
    if not chunks:
        return []

    try:
        baseline = TfidfBaseline(
            ngram_range=(1, 2),
            max_features=500,
            min_df=1,
            max_df=0.95,
        )
        baseline.fit(chunks)
    except ValueError as exc:
        logger.warning("TF-IDF baseline nao gerou vocabulario util: %s", exc)
        return []

    # Global top (mean of the scores across the chunks). Makes sense here
    # because each chunk is an independent pseudo-doc.
    raw_top = baseline.top_terms(top_n=top_n)

    # Defensive conversion: TfidfVectorizer normalizes L2 per document, so
    # the scores typically land in [0, 1]. We cap to [0, 1] to match
    # the schema constraint (`ge=0.0, le=1.0`) and avoid a 502 from validation.
    out: list[BaselineTerm] = []
    for term, score in raw_top:
        clipped = max(0.0, min(1.0, float(score)))
        out.append(BaselineTerm(term=term, score=clipped))
    return out
