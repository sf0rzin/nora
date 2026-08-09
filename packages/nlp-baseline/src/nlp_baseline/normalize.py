"""PT-BR text normalization --- deterministic and dependency-free.

Covers the minimum transformations for the TF-IDF pipeline:

* lowercasing,
* punctuation removal,
* optional digit removal,
* optional accent normalization (NFD + combining-mark filter),
* whitespace collapsing.

Deliberate implementation:

* Keeps unicode letter characters (``str.isalpha``) instead of an
  ASCII whitelist --- this preserves ``c`` after cedilla removal and
  allows accented words when ``strip_accents=False``.
* Uses stdlib ``unicodedata`` instead of heavy regex --- more predictable
  and covers the PT-BR universe with no special cases.
"""

from __future__ import annotations

import re
import unicodedata

_WHITESPACE_RE = re.compile(r"\s+")


def _strip_accents(text: str) -> str:
    """Removes accents via Unicode NFD decomposition + combining-mark filter."""
    nfd = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in nfd if not unicodedata.combining(ch))


def normalize_text(
    text: str,
    *,
    lowercase: bool = True,
    strip_accents: bool = True,
    remove_punctuation: bool = True,
    remove_digits: bool = False,
) -> str:
    """Normalizes ``text`` applying the configured transformations.

    Args:
        text: raw text.
        lowercase: forces lowercase (default True).
        strip_accents: removes accents via NFD (default True).
        remove_punctuation: replaces punctuation with a space (default True).
            Punctuation is any character whose Unicode category starts
            with ``P`` (Punctuation) or ``S`` (Symbol). That criterion
            covers comma, period, parentheses, ``#``, ``@``, emojis, etc.
        remove_digits: replaces digits with a space (default False).
        collapse_whitespace: collapses whitespace runs into a single space
            (default True, always applied last).

    Returns:
        Normalized string, with no leading/trailing spaces.
    """
    if not text:
        return ""

    if lowercase:
        text = text.lower()

    if strip_accents:
        text = _strip_accents(text)

    if remove_punctuation or remove_digits:
        out_chars: list[str] = []
        for ch in text:
            if ch.isspace():
                out_chars.append(" ")
                continue
            if remove_digits and ch.isdigit():
                out_chars.append(" ")
                continue
            if remove_punctuation:
                cat = unicodedata.category(ch)
                # P* = Punctuation, S* = Symbol/math/currency/other
                if cat.startswith(("P", "S")):
                    out_chars.append(" ")
                    continue
            out_chars.append(ch)
        text = "".join(out_chars)

    return _WHITESPACE_RE.sub(" ", text).strip()
