"""Tokenizacao PT-BR baseada em normalize + split simples + stopword filter.

Como o pipeline ja normaliza acentos por padrao, a tokenizacao casa com a
versao *normalizada* das stopwords PT-BR para evitar falsos-negativos.

Tokenizacao deliberadamente simples (split por whitespace) --- esse modulo
serve como pre-processamento generico; quem precisa de ngramas usa o
``TfidfVectorizer`` interno do ``TfidfBaseline`` (que tem ngram_range
proprio e ja faz tokenizacao internamente).
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
    """Tokeniza texto PT-BR aplicando normalizacao + split + filtragem.

    Args:
        text: texto bruto.
        remove_stopwords: filtra stopwords PT-BR (default True). Usa a lista
            normalizada ou nao em funcao de ``strip_accents``.
        strip_accents: remove acentos durante a normalizacao (default True).
        remove_digits: remove digitos (default False --- numeros podem ser
            sinal relevante em transcricoes de venda, ex.: "R$ 500 mil").
        min_token_len: descarta tokens menores que esse tamanho (default 2,
            para eliminar ruidos tipo letras soltas pos-quebra de palavra).

    Returns:
        Lista ordenada de tokens (preserva a ordem de ocorrencia).
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
