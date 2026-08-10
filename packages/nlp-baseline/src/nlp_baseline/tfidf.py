"""Interpretable TF-IDF baseline (thin wrapper over ``sklearn.TfidfVectorizer``).

Goal: to be used both in production (NLP worker --- pipeline step,
pre-LLM) and in the academic notebook, guaranteeing that the number that goes to
the report is the same number that goes to NORA's UI.

Deliberate decisions:

* No global state. Each instance is independent --- it can be built,
  trained and discarded in any order.
* The internal preprocessor applies our ``normalize_text`` before the
  ``TfidfVectorizer`` (which still uses its regex tokenizer). This guarantees
  stopwords are matched in the *normalized* form (matching ``e`` instead
  of ``é``) and that terms like ``Protheus`` and ``protheus`` become the same
  token.
* Stopwords come from ``nlp_baseline.stopwords`` (hardcoded PT-BR list) --- we do
  not depend on NLTK at runtime.
* The scores exposed by ``top_terms`` are the tfidf *mean* per document;
  those of ``top_terms_per_doc`` are the tfidf vector of the isolated document. In
  both cases, we sort by score desc and break ties by term
  (lex asc) --- deterministic behavior.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

from .normalize import normalize_text
from .stopwords import get_ptbr_stopwords_normalized


class TfidfBaseline:
    """Interpretable PT-BR TF-IDF wrapper.

    Example:
        >>> baseline = TfidfBaseline()
        >>> baseline.fit(["renovacao do contrato", "proposta comercial nova"])
        >>> baseline.top_terms(top_n=3)
        [('contrato', ...), ('proposta', ...), ('renovacao', ...)]
    """

    def __init__(
        self,
        *,
        ngram_range: tuple[int, int] = (1, 2),
        max_features: int = 500,
        min_df: int | float = 1,
        max_df: float = 0.95,
        sublinear_tf: bool = True,
    ) -> None:
        """Configures the TF-IDF hyperparameters.

        Args:
            ngram_range: (1, 2) covers unigrams + bigrams --- good for PT-BR
                because it captures compound terms like "valor mensal".
            max_features: limits the vocabulary to the N most frequent terms
                (controls memory/time cost in the notebook and in the worker).
            min_df: document minimum frequency. Integer = absolute count;
                float = proportion.
            max_df: discards terms appearing in more than ``max_df`` of the
                documents (filters noise --- an omnipresent term does not discriminate).
                When ``len(texts) == 1``, we internally turn this filter off
                to avoid sklearn's ``ValueError`` ("max_df < min_df").
            sublinear_tf: applies ``1 + log(tf)`` --- recommended for
                medium/long texts (transcripts).
        """
        if ngram_range[0] < 1 or ngram_range[1] < ngram_range[0]:
            raise ValueError(f"invalid ngram_range: {ngram_range}")
        if max_features <= 0:
            raise ValueError(f"max_features must be > 0: {max_features}")
        if not 0.0 < max_df <= 1.0:
            raise ValueError(f"max_df out of range (0, 1]: {max_df}")

        self._ngram_range = ngram_range
        self._max_features = max_features
        self._min_df = min_df
        self._max_df = max_df
        self._sublinear_tf = sublinear_tf

        self._stopwords: list[str] = get_ptbr_stopwords_normalized()
        self._vectorizer: TfidfVectorizer | None = None
        self._tfidf_matrix: Any | None = None  # scipy sparse matrix
        self._mean_scores: np.ndarray | None = None
        self._feature_names: list[str] = []
        self._n_docs: int = 0

    # ---------- Preprocessing ----------

    @staticmethod
    def _preprocess(text: str) -> str:
        """Hook called by ``TfidfVectorizer`` before tokenization."""
        return normalize_text(
            text,
            lowercase=True,
            strip_accents=True,
            remove_punctuation=True,
            remove_digits=False,
        )

    def _build_vectorizer(self, n_docs: int) -> TfidfVectorizer:
        """Builds the ``TfidfVectorizer`` adjusting impossible filters."""
        max_df: float = self._max_df
        min_df: int | float = self._min_df

        # Degenerate case: 1 document --- proportional ``max_df`` <1 breaks
        # because every term appears in 100% of the docs. We turn the filter off.
        if n_docs <= 1 and isinstance(max_df, float) and max_df < 1.0:
            max_df = 1.0
        # Impossible proportional ``min_df``: if 2/2 docs fall out,
        # it breaks. We force 1 when there are few docs.
        if isinstance(min_df, float) and min_df * n_docs < 1.0:
            min_df = 1

        return TfidfVectorizer(
            preprocessor=self._preprocess,
            stop_words=self._stopwords,
            ngram_range=self._ngram_range,
            max_features=self._max_features,
            min_df=min_df,
            max_df=max_df,
            sublinear_tf=self._sublinear_tf,
            lowercase=False,  # already done in the preprocessor
            strip_accents=None,  # same
        )

    # ---------- Fit / Query ----------

    def fit(self, texts: list[str]) -> "TfidfBaseline":
        """Trains the vectorizer on the ``texts`` corpus (list of documents).

        Returns:
            ``self`` (chainable).

        Raises:
            ValueError: empty corpus or all texts empty after
                normalization (sklearn raises ``empty vocabulary``; we convert it
                into a more useful message).
        """
        if not texts:
            raise ValueError("Empty corpus. Provide at least 1 document.")

        self._n_docs = len(texts)
        self._vectorizer = self._build_vectorizer(n_docs=self._n_docs)

        try:
            matrix = self._vectorizer.fit_transform(texts)
        except ValueError as exc:
            raise ValueError(
                "Corpus produced no useful vocabulary "
                "(all texts are empty or made up only of stopwords)."
            ) from exc

        self._tfidf_matrix = matrix
        self._feature_names = list(self._vectorizer.get_feature_names_out())
        # Mean of the scores per feature across the documents.
        # ``matrix.mean(axis=0)`` returns ``np.matrix``; we convert to a 1D
        # array for direct use in ``argsort``.
        self._mean_scores = np.asarray(matrix.mean(axis=0)).ravel()
        return self

    def _require_fitted(self) -> None:
        if self._vectorizer is None or self._mean_scores is None:
            raise RuntimeError(
                "TfidfBaseline has not been trained yet. Call .fit(texts) first."
            )

    def top_terms(self, top_n: int = 20) -> list[tuple[str, float]]:
        """Top ``top_n`` terms of the corpus, sorted by mean score desc.

        Ties are resolved by the ascending lexicographic order of the term ---
        deterministic behavior for tests and logs.
        """
        self._require_fitted()
        if top_n <= 0:
            return []

        assert self._mean_scores is not None
        scores = self._mean_scores
        # ``argsort`` is ascending; we take the indices from the end for descending.
        # To guarantee determinism on ties, we sort by (-score, term).
        indexed = sorted(
            range(len(self._feature_names)),
            key=lambda i: (-float(scores[i]), self._feature_names[i]),
        )
        out: list[tuple[str, float]] = []
        for idx in indexed[:top_n]:
            score = float(scores[idx])
            if score <= 0.0:
                break
            out.append((self._feature_names[idx], score))
        return out

    def top_terms_per_doc(
        self, text: str, top_n: int = 10
    ) -> list[tuple[str, float]]:
        """Top ``top_n`` terms for ``text`` according to the learned vocabulary.

        Terms outside the vocabulary are ignored (sklearn's default
        behavior). Ties follow the same deterministic criterion as ``top_terms``.
        """
        self._require_fitted()
        if top_n <= 0:
            return []
        assert self._vectorizer is not None

        vec = self._vectorizer.transform([text])  # shape (1, n_features)
        arr = np.asarray(vec.todense()).ravel()
        indexed = sorted(
            range(len(self._feature_names)),
            key=lambda i: (-float(arr[i]), self._feature_names[i]),
        )
        out: list[tuple[str, float]] = []
        for idx in indexed[:top_n]:
            score = float(arr[idx])
            if score <= 0.0:
                break
            out.append((self._feature_names[idx], score))
        return out

    # ---------- Introspection ----------

    @property
    def vocabulary_size(self) -> int:
        """Number of learned terms. Zero before the ``fit``."""
        return len(self._feature_names)

    @property
    def n_docs(self) -> int:
        """Number of documents used in the ``fit``."""
        return self._n_docs

    @property
    def feature_names(self) -> list[str]:
        """List of learned terms (copy)."""
        return list(self._feature_names)
