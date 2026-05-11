"""Normalizacao de texto PT-BR --- determinista e sem dependencias.

Cobre as transformacoes minimas para o pipeline TF-IDF:

* lowercasing,
* remocao de pontuacao,
* remocao opcional de digitos,
* normalizacao opcional de acentos (NFD + filtro de combining marks),
* colapso de espacos.

Implementacao consciente:

* Mantem caracteres unicode de letras (``str.isalpha``) ao invez de uma
  whitelist ASCII --- isso preserva ``c`` apos a remocao de cedilha e
  permite palavras com acento quando ``strip_accents=False``.
* Usa ``unicodedata`` da stdlib em vez de regex pesado --- mais previsivel
  e cobre o universo PT-BR sem casos especiais.
"""

from __future__ import annotations

import re
import unicodedata

_WHITESPACE_RE = re.compile(r"\s+")


def _strip_accents(text: str) -> str:
    """Remove acentos via decomposicao Unicode NFD + filtro de combining marks."""
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
    """Normaliza ``text`` aplicando as transformacoes configuradas.

    Args:
        text: texto bruto.
        lowercase: forca lowercase (default True).
        strip_accents: remove acentos via NFD (default True).
        remove_punctuation: substitui pontuacao por espaco (default True).
            Considera-se pontuacao qualquer caractere cuja categoria Unicode
            comece com ``P`` (Punctuation) ou ``S`` (Symbol). Esse criterio
            cobre virgula, ponto, parenteses, ``#``, ``@``, emojis, etc.
        remove_digits: substitui digitos por espaco (default False).
        collapse_whitespace: colapsa runs de whitespace em um espaco unico
            (default True, sempre aplicado por ultimo).

    Returns:
        String normalizada, sem espacos no inicio/fim.
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
