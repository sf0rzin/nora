"""TF-IDF baseline interpretavel (wrapper fino sobre ``sklearn.TfidfVectorizer``).

Objetivo: ser usado tanto em producao (worker NLP --- step do pipeline,
pre-LLM) quanto no notebook academico, garantindo que o numero que vai pro
relatorio eh o mesmo numero que vai pra UI da NORA.

Decisoes deliberadas:

* Sem state global. Cada instancia eh independente --- pode ser construida,
  treinada e descartada em qualquer ordem.
* O preprocessador interno aplica o nosso ``normalize_text`` antes do
  ``TfidfVectorizer`` (que ainda assim usa seu tokenizer regex). Isso garante
  que stopwords sao deduzidas da forma *normalizada* (matchear ``e`` em vez
  de ``é``) e que termos como ``Protheus`` e ``protheus`` viram o mesmo
  token.
* Stopwords vem do ``nlp_baseline.stopwords`` (lista PT-BR hardcoded) --- nao
  dependemos do NLTK em runtime.
* Os scores expostos por ``top_terms`` sao a *media* do tfidf por documento;
  os de ``top_terms_per_doc`` sao o vetor tfidf do documento isolado. Em
  ambos os casos, ordenamos por score desc e quebramos empate por termo
  (lex asc) --- comportamento determinista.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

from .normalize import normalize_text
from .stopwords import get_ptbr_stopwords_normalized


class TfidfBaseline:
    """Wrapper interpretavel de TF-IDF PT-BR.

    Exemplo:
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
        """Configura os hiperparametros do TF-IDF.

        Args:
            ngram_range: (1, 2) cobre unigramas + bigramas --- bom para PT-BR
                porque captura termos compostos como "valor mensal".
            max_features: limita o vocabulario aos N termos mais frequentes
                (controla custo de memoria/tempo no notebook e no worker).
            min_df: documento minimum frequency. Inteiro = contagem absoluta;
                float = proporcao.
            max_df: descarta termos que aparecem em mais de ``max_df`` dos
                documentos (filtra ruido --- termo onipresente nao discrimina).
                Quando ``len(texts) == 1``, internamente desligamos esse filtro
                para evitar ``ValueError`` do sklearn ("max_df < min_df").
            sublinear_tf: aplica ``1 + log(tf)`` --- recomendado para textos
                medios/longos (transcricoes).
        """
        if ngram_range[0] < 1 or ngram_range[1] < ngram_range[0]:
            raise ValueError(f"ngram_range invalido: {ngram_range}")
        if max_features <= 0:
            raise ValueError(f"max_features deve ser > 0: {max_features}")
        if not 0.0 < max_df <= 1.0:
            raise ValueError(f"max_df fora do intervalo (0, 1]: {max_df}")

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

    # ---------- Pre-processamento ----------

    @staticmethod
    def _preprocess(text: str) -> str:
        """Hook chamado pelo ``TfidfVectorizer`` antes da tokenizacao."""
        return normalize_text(
            text,
            lowercase=True,
            strip_accents=True,
            remove_punctuation=True,
            remove_digits=False,
        )

    def _build_vectorizer(self, n_docs: int) -> TfidfVectorizer:
        """Constroi o ``TfidfVectorizer`` ajustando filtros impossiveis."""
        max_df: float = self._max_df
        min_df: int | float = self._min_df

        # Caso degenerado: 1 documento --- ``max_df`` proportional <1 quebra
        # porque todo termo aparece em 100% dos docs. Desligamos o filtro.
        if n_docs <= 1 and isinstance(max_df, float) and max_df < 1.0:
            max_df = 1.0
        # ``min_df`` proporcional impossivel: se 2/2 docs caem fora,
        # quebra. Forcamos 1 quando ha poucos docs.
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
            lowercase=False,  # ja feito no preprocessor
            strip_accents=None,  # idem
        )

    # ---------- Fit / Query ----------

    def fit(self, texts: list[str]) -> "TfidfBaseline":
        """Treina o vectorizer no corpus ``texts`` (lista de documentos).

        Returns:
            ``self`` (encadeavel).

        Raises:
            ValueError: corpus vazio ou todos os textos vazios apos a
                normalizacao (sklearn levanta ``empty vocabulary``; convertemos
                para uma mensagem mais util).
        """
        if not texts:
            raise ValueError("Corpus vazio. Forneca ao menos 1 documento.")

        self._n_docs = len(texts)
        self._vectorizer = self._build_vectorizer(n_docs=self._n_docs)

        try:
            matrix = self._vectorizer.fit_transform(texts)
        except ValueError as exc:
            raise ValueError(
                "Corpus nao gerou vocabulario util "
                "(todos os textos vazios ou compostos so de stopwords)."
            ) from exc

        self._tfidf_matrix = matrix
        self._feature_names = list(self._vectorizer.get_feature_names_out())
        # Media dos scores por feature ao longo dos documentos.
        # ``matrix.mean(axis=0)`` retorna ``np.matrix``; convertemos pra array
        # 1D para uso direto em ``argsort``.
        self._mean_scores = np.asarray(matrix.mean(axis=0)).ravel()
        return self

    def _require_fitted(self) -> None:
        if self._vectorizer is None or self._mean_scores is None:
            raise RuntimeError(
                "TfidfBaseline ainda nao foi treinado. Chame .fit(texts) antes."
            )

    def top_terms(self, top_n: int = 20) -> list[tuple[str, float]]:
        """Top ``top_n`` termos do corpus, ordenados por score medio desc.

        Empate eh resolvido pela ordem lexicografica ascendente do termo ---
        comportamento determinista para testes e logs.
        """
        self._require_fitted()
        if top_n <= 0:
            return []

        assert self._mean_scores is not None
        scores = self._mean_scores
        # ``argsort`` ascendente; pegamos os indices do final para descendente.
        # Para garantir determinismo no empate, ordenamos por (-score, term).
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
        """Top ``top_n`` termos para ``text`` segundo o vocabulario aprendido.

        Termos fora do vocabulario sao ignorados (comportamento padrao do
        sklearn). Empates seguem o mesmo criterio determinista de ``top_terms``.
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

    # ---------- Introspeccao ----------

    @property
    def vocabulary_size(self) -> int:
        """Numero de termos aprendidos. Zero antes do ``fit``."""
        return len(self._feature_names)

    @property
    def n_docs(self) -> int:
        """Numero de documentos usados no ``fit``."""
        return self._n_docs

    @property
    def feature_names(self) -> list[str]:
        """Lista de termos aprendidos (copia)."""
        return list(self._feature_names)
