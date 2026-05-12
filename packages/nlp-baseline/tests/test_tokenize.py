"""Testes da funcao ``tokenize_ptbr``.

Cobre:
* split correto apos normalizacao
* remocao de stopwords PT-BR padrao
* preservacao de palavras de conteudo
* respeitar flags (remove_stopwords, strip_accents, remove_digits)
"""

from __future__ import annotations

from nlp_baseline import get_ptbr_stopwords, tokenize_ptbr


def test_tokenize_empty_returns_empty() -> None:
    assert tokenize_ptbr("") == []
    assert tokenize_ptbr("   ") == []


def test_tokenize_splits_on_whitespace_and_punctuation() -> None:
    tokens = tokenize_ptbr("Vamos fechar contrato, hoje!", remove_stopwords=False)
    # Apos normalizacao + remocao de stopwords desligada:
    # "vamos fechar contrato hoje"
    assert tokens == ["vamos", "fechar", "contrato", "hoje"]


def test_tokenize_removes_common_ptbr_stopwords_by_default() -> None:
    # "o", "a", "de", "para" precisam sumir
    text = "enviar a proposta para o cliente final do projeto"
    tokens = tokenize_ptbr(text)
    assert "o" not in tokens
    assert "a" not in tokens
    assert "de" not in tokens
    assert "do" not in tokens
    assert "para" not in tokens
    # Termos de conteudo continuam
    assert "enviar" in tokens
    assert "proposta" in tokens
    assert "cliente" in tokens
    assert "final" in tokens
    assert "projeto" in tokens


def test_tokenize_keeps_stopwords_when_disabled() -> None:
    # Stopwords com 2+ chars: "do", "para" passariam pelo min_token_len.
    tokens = tokenize_ptbr(
        "do produto para o cliente", remove_stopwords=False
    )
    assert "do" in tokens
    assert "para" in tokens
    # "o" tem 1 char e cai pelo min_token_len, independente de stopword.
    assert "o" not in tokens


def test_tokenize_min_token_len_drops_single_char_words() -> None:
    """Tokens de 1 char sao sempre dropados (default min_token_len=2)."""
    tokens = tokenize_ptbr("o a e produto", remove_stopwords=False)
    assert "o" not in tokens
    assert "a" not in tokens
    assert "e" not in tokens
    assert "produto" in tokens


def test_tokenize_strips_accents_by_default() -> None:
    tokens = tokenize_ptbr("decisão estratégica para renovação")
    # apos strip_accents: "decisao estrategica para renovacao"
    # "para" eh stopword, removida.
    assert "decisao" in tokens
    assert "estrategica" in tokens
    assert "renovacao" in tokens
    assert "para" not in tokens


def test_tokenize_can_keep_accents() -> None:
    tokens = tokenize_ptbr("decisão", strip_accents=False, remove_stopwords=False)
    assert tokens == ["decisão"]


def test_tokenize_filters_short_tokens() -> None:
    # ``min_token_len=2`` por padrao --- letra unica eh dropada
    tokens = tokenize_ptbr("a b c proposta", remove_stopwords=False)
    assert tokens == ["proposta"]


def test_tokenize_respects_min_token_len_explicit() -> None:
    tokens = tokenize_ptbr(
        "ab cd efgh ij", remove_stopwords=False, min_token_len=3
    )
    assert tokens == ["efgh"]


def test_tokenize_keeps_digits_by_default() -> None:
    tokens = tokenize_ptbr("contrato vale 500 mil", remove_stopwords=False)
    assert "500" in tokens


def test_tokenize_can_remove_digits() -> None:
    tokens = tokenize_ptbr(
        "contrato vale 500 mil", remove_stopwords=False, remove_digits=True
    )
    assert "500" not in tokens
    assert "contrato" in tokens
    assert "mil" in tokens


def test_get_ptbr_stopwords_contains_common_words() -> None:
    sw = get_ptbr_stopwords()
    # Esses sao indispensaveis em qualquer lista decente de stopwords PT-BR
    for w in ("o", "a", "de", "para", "e", "do", "da", "que"):
        assert w in sw, f"stopword '{w}' deveria estar na lista"


def test_get_ptbr_stopwords_returns_fresh_list() -> None:
    # Mutar nao afeta a proxima chamada.
    sw1 = get_ptbr_stopwords()
    sw1.append("PALAVRA_INVENTADA_XYZ")
    sw2 = get_ptbr_stopwords()
    assert "PALAVRA_INVENTADA_XYZ" not in sw2


def test_get_ptbr_stopwords_is_lowercased_and_deduped() -> None:
    sw = get_ptbr_stopwords()
    # Sem duplicatas
    assert len(sw) == len(set(sw))
    # Tudo lowercase
    for w in sw:
        assert w == w.lower(), f"stopword nao normalizada: '{w}'"
