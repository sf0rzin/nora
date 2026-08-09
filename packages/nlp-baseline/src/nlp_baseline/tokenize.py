"""PT-BR tokenization based on normalize + simple split + stopword filter.

Since the pipeline already normalizes accents by default, tokenization matches the
*normalized* version of the PT-BR stopwords to avoid false negatives.

Deliberately simple tokenization (split on whitespace) --- this module
serves as generic preprocessing; whoever needs ngrams uses the
``TfidfVectorizer`` internal to ``TfidfBaseline`` (which has its own
ngram_range and already tokenizes internally).
"""

from __future__ import annotations

from .normalize import normalize_text
from .stopwords import get_ptbr_stopwords, get_ptbr_stopwords_normalized


def tokenize_ptbr(
    text: str,
    *,
    remove_stopwords: bool = True,
    strip_accents: bool = True,
    remove_digits: bool = False,
    min_token_len: int = 2,
) -> list[str]:
    """Tokenizes PT-BR text applying normalization + split + filtering.

    Args:
        text: raw text.
        remove_stopwords: filters PT-BR stopwords (default True). Uses the
            normalized list or not depending on ``strip_accents``.
        strip_accents: removes accents during normalization (default True).
        remove_digits: removes digits (default False --- numbers can be a
            relevant signal in sales transcripts, e.g.: "R$ 500 mil").
        min_token_len: discards tokens shorter than this length (default 2,
            to eliminate noise like stray letters after a word break).

    Returns:
        Ordered list of tokens (preserves the order of occurrence).
    """
    if not text:
        return []

    normalized = normalize_text(
        text,
        lowercase=True,
        strip_accents=strip_accents,
        remove_punctuation=True,
        remove_digits=remove_digits,
    )

    raw_tokens = normalized.split()
    if not raw_tokens:
        return []

    if not remove_stopwords:
        return [tok for tok in raw_tokens if len(tok) >= min_token_len]

    stopset = set(
        get_ptbr_stopwords_normalized() if strip_accents else get_ptbr_stopwords()
    )
    return [
        tok
        for tok in raw_tokens
        if len(tok) >= min_token_len and tok not in stopset
    ]
