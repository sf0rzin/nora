"""Tests for the ``TfidfBaseline`` class.

Covers:
* fit + top_terms (minimal synthetic PT-BR corpus)
* top_terms_per_doc returns relevant terms of the doc
* multiple instances do not share state
* degenerate cases (empty corpus, top_n=0, etc.)
"""

from __future__ import annotations

import pytest

from nlp_baseline import TfidfBaseline

# Minimal synthetic PT-BR corpus --- 4 documents about meetings/sales.
SAMPLE_CORPUS: list[str] = [
    "Cliente aprovou a proposta de renovacao do contrato anual.",
    "Vamos enviar a proposta comercial nova com desconto.",
    "Renovacao do contrato anual ficou aprovada pela diretoria.",
    "Concorrente apresentou solucao mais barata, atenção ao risco.",
]


def test_fit_returns_self_for_chaining() -> None:
    baseline = TfidfBaseline()
    result = baseline.fit(SAMPLE_CORPUS)
    assert result is baseline


def test_fit_raises_on_empty_corpus() -> None:
    baseline = TfidfBaseline()
    with pytest.raises(ValueError):
        baseline.fit([])


def test_fit_raises_on_all_stopwords_corpus() -> None:
    baseline = TfidfBaseline()
    with pytest.raises(ValueError):
        # All stopwords/punctuation --- generates no vocabulary.
        baseline.fit(["o a de e", "para com o", "a e o"])


def test_vocabulary_size_grows_after_fit() -> None:
    baseline = TfidfBaseline()
    assert baseline.vocabulary_size == 0
    baseline.fit(SAMPLE_CORPUS)
    assert baseline.vocabulary_size > 0


def test_n_docs_reflects_corpus_size() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    assert baseline.n_docs == len(SAMPLE_CORPUS)


def test_top_terms_returns_list_of_tuples_with_positive_scores() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top = baseline.top_terms(top_n=10)
    assert top, "top_terms should not be empty for a valid corpus"
    for term, score in top:
        assert isinstance(term, str)
        assert isinstance(score, float)
        assert score > 0.0


def test_top_terms_sorted_descending_by_score() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top = baseline.top_terms(top_n=10)
    scores = [s for _, s in top]
    assert scores == sorted(scores, reverse=True)


def test_top_terms_top_n_limits_result() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top_3 = baseline.top_terms(top_n=3)
    assert len(top_3) <= 3


def test_top_terms_zero_returns_empty() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    assert baseline.top_terms(top_n=0) == []


def test_top_terms_requires_fit() -> None:
    baseline = TfidfBaseline()
    with pytest.raises(RuntimeError):
        baseline.top_terms(top_n=5)


def test_top_terms_captures_domain_signal() -> None:
    """A recurring corpus term should show up in the top."""
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top = baseline.top_terms(top_n=20)
    terms = {t for t, _ in top}
    # "proposta" appears in 2/4 docs; "contrato" in 2/4; "renovacao" in 2/4.
    # At least one of these three must climb the ranking.
    assert terms & {"proposta", "contrato", "renovacao"}


def test_top_terms_per_doc_returns_terms_for_doc() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top = baseline.top_terms_per_doc(SAMPLE_CORPUS[0], top_n=5)
    assert top
    for term, score in top:
        assert isinstance(term, str)
        assert isinstance(score, float)
        assert score > 0.0


def test_top_terms_per_doc_reflects_document_content() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    # Document about a competitor --- "concorrente" or "solucao" must show up.
    competitor_doc = SAMPLE_CORPUS[3]
    top = baseline.top_terms_per_doc(competitor_doc, top_n=10)
    terms = {t for t, _ in top}
    assert terms & {"concorrente", "solucao", "barata", "risco"}


def test_top_terms_per_doc_requires_fit() -> None:
    baseline = TfidfBaseline()
    with pytest.raises(RuntimeError):
        baseline.top_terms_per_doc("texto qualquer", top_n=5)


def test_top_terms_per_doc_zero_returns_empty() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    assert baseline.top_terms_per_doc(SAMPLE_CORPUS[0], top_n=0) == []


def test_top_terms_per_doc_handles_unknown_words() -> None:
    """Text with only out-of-vocabulary words returns an empty list."""
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    top = baseline.top_terms_per_doc("xyzfoobarbaz nadaqui", top_n=5)
    assert top == []


def test_multiple_instances_have_independent_state() -> None:
    """No global state --- different instances do not interfere with each other."""
    corpus_a = ["proposta comercial nova", "fechar negocio rapido"]
    corpus_b = ["risco churn cliente", "concorrente preco abaixo"]

    bl_a = TfidfBaseline().fit(corpus_a)
    bl_b = TfidfBaseline().fit(corpus_b)

    terms_a = {t for t, _ in bl_a.top_terms(top_n=10)}
    terms_b = {t for t, _ in bl_b.top_terms(top_n=10)}

    assert "proposta" in terms_a or "comercial" in terms_a or "negocio" in terms_a
    assert "risco" in terms_b or "concorrente" in terms_b or "churn" in terms_b
    # Disjoint vocabularies (indirect check of isolation)
    assert bl_a.feature_names != bl_b.feature_names


def test_ngram_range_unigrams_only() -> None:
    """Checks that ngram_range=(1,1) generates only unigrams."""
    baseline = TfidfBaseline(ngram_range=(1, 1))
    baseline.fit(SAMPLE_CORPUS)
    for term in baseline.feature_names:
        assert " " not in term, f"expected a unigram, got '{term}'"


def test_ngram_range_bigrams_included() -> None:
    """ngram_range=(1,2) must generate at least one bigram in the test corpus."""
    baseline = TfidfBaseline(ngram_range=(1, 2))
    baseline.fit(SAMPLE_CORPUS)
    bigrams = [t for t in baseline.feature_names if " " in t]
    assert bigrams, "Expected at least one bigram in the vocabulary"


def test_max_features_caps_vocabulary() -> None:
    baseline = TfidfBaseline(max_features=5)
    baseline.fit(SAMPLE_CORPUS)
    assert baseline.vocabulary_size <= 5


def test_invalid_ngram_range_raises() -> None:
    with pytest.raises(ValueError):
        TfidfBaseline(ngram_range=(2, 1))
    with pytest.raises(ValueError):
        TfidfBaseline(ngram_range=(0, 1))


def test_invalid_max_features_raises() -> None:
    with pytest.raises(ValueError):
        TfidfBaseline(max_features=0)


def test_invalid_max_df_raises() -> None:
    with pytest.raises(ValueError):
        TfidfBaseline(max_df=1.5)
    with pytest.raises(ValueError):
        TfidfBaseline(max_df=0.0)


def test_single_document_corpus_does_not_crash() -> None:
    """Degenerate case: 1 document. Must not break because of max_df."""
    baseline = TfidfBaseline()
    baseline.fit(["proposta comercial nova com preco competitivo"])
    top = baseline.top_terms(top_n=5)
    assert top, "should extract terms even with 1 doc"


def test_feature_names_returns_copy() -> None:
    baseline = TfidfBaseline()
    baseline.fit(SAMPLE_CORPUS)
    names_a = baseline.feature_names
    names_a.append("INJECTED")
    names_b = baseline.feature_names
    assert "INJECTED" not in names_b
