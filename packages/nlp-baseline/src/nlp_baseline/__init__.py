"""NLP Baseline (TF-IDF PT-BR) shared between the NORA worker and the academic notebook.

Decision recorded in ADR 0010. The purpose of the package is to provide a
single auditable implementation of the interpretable baseline required by the
academic plan (FIAP Challenge — `docs/challenge/fiap-challenge-2026.md`)
reusable in production as a step of the worker pipeline
(`services/nlp-worker/src/nora_nlp/services/baseline.py`).

Minimal usage:

    from nlp_baseline import TfidfBaseline

    baseline = TfidfBaseline()
    baseline.fit(["texto 1", "texto 2", "texto 3"])
    print(baseline.top_terms(top_n=10))
"""

from .normalize import normalize_text
from .stopwords import get_ptbr_stopwords
from .tfidf import TfidfBaseline
from .tokenize import tokenize_ptbr

__all__ = [
    "TfidfBaseline",
    "get_ptbr_stopwords",
    "normalize_text",
    "tokenize_ptbr",
]

__version__ = "0.1.0"
