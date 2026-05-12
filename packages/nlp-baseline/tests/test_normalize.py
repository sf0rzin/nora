"""Testes da funcao ``normalize_text``.

Cobre os comportamentos prometidos no docstring:
* lowercase
* remocao de pontuacao
* remocao opcional de digitos
* normalizacao opcional de acentos
* colapso de whitespace
"""

from __future__ import annotations

from nlp_baseline import normalize_text


def test_normalize_empty_returns_empty() -> None:
    assert normalize_text("") == ""


def test_normalize_lowercases_by_default() -> None:
    assert normalize_text("CARLOS Comprou o Produto") == "carlos comprou o produto"


def test_normalize_can_disable_lowercase() -> None:
    out = normalize_text("Carlos Comprou", lowercase=False, strip_accents=False)
    assert out == "Carlos Comprou"


def test_normalize_removes_punctuation_by_default() -> None:
    out = normalize_text("ola, mundo! tudo bem? sim.", strip_accents=False)
    assert out == "ola mundo tudo bem sim"


def test_normalize_preserves_punctuation_when_disabled() -> None:
    out = normalize_text(
        "ola, mundo!",
        remove_punctuation=False,
        strip_accents=False,
    )
    assert "," in out
    assert "!" in out


def test_normalize_strips_accents_by_default() -> None:
    out = normalize_text("decisão estratégica também é açúcar")
    # Sem acento depois do strip_accents=True (default)
    assert out == "decisao estrategica tambem e acucar"


def test_normalize_can_keep_accents() -> None:
    out = normalize_text(
        "decisão", strip_accents=False, remove_punctuation=False
    )
    assert out == "decisão"


def test_normalize_can_remove_digits() -> None:
    out = normalize_text("contrato vale R$ 500 mil", remove_digits=True)
    # "R$" vira espaco (S category), "500" vira espacos, sobra "contrato vale mil"
    assert "500" not in out
    assert "contrato" in out
    assert "vale" in out
    assert "mil" in out


def test_normalize_keeps_digits_by_default() -> None:
    out = normalize_text("contrato vale 500 mil")
    assert "500" in out


def test_normalize_collapses_whitespace() -> None:
    out = normalize_text("muito\t  texto\n\n   com   espaco")
    assert out == "muito texto com espaco"


def test_normalize_strips_leading_trailing_whitespace() -> None:
    out = normalize_text("   ola   ")
    assert out == "ola"


def test_normalize_handles_unicode_symbols() -> None:
    # Emojis caem em categoria S* --> tratado como pontuacao
    out = normalize_text("vendido 😀 com sucesso", strip_accents=False)
    assert "😀" not in out
    assert "vendido" in out
    assert "sucesso" in out


def test_normalize_cedilha_becomes_c() -> None:
    out = normalize_text("açúcar")
    # 'ç' + acentos viram 'c' apos strip_accents (NFD remove combining marks)
    assert out == "acucar"
