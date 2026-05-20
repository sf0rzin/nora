"""NLP Baseline (TF-IDF PT-BR) compartilhado entre worker NORA e notebook academico.

Decisao registrada na ADR 0010. O proposito do package eh fornecer uma
implementacao unica e auditavel do baseline interpretavel exigido pelo plano
academico (FIAP Challenge — `docs/challenge/fiap-challenge-2026.md`)
reutilizavel em producao como step do pipeline do worker
(`services/nlp-worker/src/nora_nlp/services/baseline.py`).

Uso minimo:

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
