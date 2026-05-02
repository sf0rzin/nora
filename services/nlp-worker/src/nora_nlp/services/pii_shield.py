"""PII Shield baseline: regex deterministico aplicado antes de qualquer chamada ao LLM.

Cobre os tipos basicos brasileiros (e-mail, telefone, CPF, CNPJ, cartao). Para PII
contextual (nomes proprios), um fallback baseado em LLM existe via prompts/pii-shield-v1.md
mas e opcional no MVP.
"""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from dataclasses import dataclass

from ..models import PiiRedactionV1, PiiType, Redaction

_EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}")
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?55\s?)?\(?\d{2}\)?[\s.\-]?\d{4,5}[\s.\-]?\d{4}(?!\d)")
_CPF_RE = re.compile(r"(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")
_CNPJ_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}(?!\d)")
_CARD_RE = re.compile(r"(?<!\d)(?:\d{4}[\s\-]?){3}\d{4}(?!\d)")

_PATTERNS: list[tuple[PiiType, re.Pattern[str]]] = [
    (PiiType.EMAIL, _EMAIL_RE),
    (PiiType.CPF, _CPF_RE),
    (PiiType.CNPJ, _CNPJ_RE),
    (PiiType.CREDIT_CARD, _CARD_RE),
    (PiiType.PHONE, _PHONE_RE),
]


@dataclass(frozen=True)
class _Match:
    type: PiiType
    start: int
    end: int
    value: str


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def redact(text: str) -> PiiRedactionV1:
    """Substitui PII por placeholders no formato `[[TIPO_N]]`.

    Retorna o texto redigido + lista de redacoes (sem o valor original em claro).
    """
    matches: list[_Match] = []
    for pii_type, pattern in _PATTERNS:
        for m in pattern.finditer(text):
            matches.append(_Match(type=pii_type, start=m.start(), end=m.end(), value=m.group(0)))

    # ordena por posicao para reescrever sem deslocar offsets das proximas
    matches.sort(key=lambda x: x.start)

    counters: dict[PiiType, int] = defaultdict(int)
    redactions: list[Redaction] = []
    rebuilt: list[str] = []
    cursor = 0

    for m in matches:
        if m.start < cursor:  # overlap, ignora
            continue
        counters[m.type] += 1
        placeholder = f"[[{m.type.value}_{counters[m.type]}]]"
        rebuilt.append(text[cursor : m.start])
        rebuilt.append(placeholder)
        redactions.append(
            Redaction(placeholder=placeholder, type=m.type, originalHash=_hash(m.value))
        )
        cursor = m.end

    rebuilt.append(text[cursor:])
    return PiiRedactionV1(redactedText="".join(rebuilt), redactions=redactions)
