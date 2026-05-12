"""Baseline TF-IDF step do pipeline NLP.

Decisao: o calculo do TF-IDF mora no package ``nlp_baseline`` (sklearn puro,
sem deps NLTK em runtime). Este modulo eh apenas o adaptador entre o pipeline
do worker e o ``TfidfBaseline``: chunka a transcricao em pseudo-documentos,
roda o fit, retorna os top termos no formato ``BaselineTerm`` do worker.

Ver ADR 0010 para a decisao de package compartilhado.

Posicao no pipeline (router /analyze):
    1. PII Shield redacao   <-- ja foi aplicado quando este modulo eh chamado.
    2. *extract_baseline_terms(safe_transcript)*  <-- aqui.
    3. (paralelo) LLM analyze.
    4. anexa baseline_terms ao response.

O texto que entra aqui ja eh ``safe_transcript`` (placeholders ``[[EMAIL_1]]``
substituiram PII). Como o TF-IDF interno normaliza acentos e pontuacao, os
placeholders viram tokens estranhos (ex.: ``email``) e ficam filtrados ou
desimportantes pelo IDF --- comportamento aceitavel para baseline.
"""

from __future__ import annotations

import logging
import re

from nlp_baseline import TfidfBaseline

from ..models import BaselineTerm

logger = logging.getLogger(__name__)

# Tamanho minimo de chunk em caracteres. Abaixo disso o IDF nao discrimina.
_MIN_CHUNK_CHARS = 80
# Numero alvo de chunks --- mais que isso costuma fragmentar demais um
# transcript curto e produzir ruido; menos elimina o sinal multi-segmento.
_TARGET_CHUNKS = 6


def _chunk_transcript(transcript: str) -> list[str]:
    """Quebra transcript em pseudo-documentos para o TF-IDF.

    Estrategia:
    * Tenta quebrar por turnos de fala (linhas com prefixo de speaker).
    * Se o resultado tiver poucos turnos (< 2), cai para split em paragrafos.
    * Se ainda for muito curto, retorna o transcript inteiro como 1 doc.

    Razao: TF-IDF com 1 unico documento funciona (TfidfBaseline trata o caso
    degenerado), mas perde discriminacao. Com 3-6 chunks o IDF comeca a fazer
    sentido --- ele penaliza termos que aparecem em todo turno.
    """
    if not transcript or not transcript.strip():
        return []

    # 1) Turnos --- linhas que casam ``Nome: ...`` ou ``[timestamp] Nome: ...``
    speaker_re = re.compile(r"^(?:\[[^\]]+\]\s*)?[A-Z][\wáéíóúãâêôç ]{0,40}:\s*", re.MULTILINE)
    turns_by_speaker: list[str] = []
    matches = list(speaker_re.finditer(transcript))
    if len(matches) >= 2:
        for i, m in enumerate(matches):
            start = m.end()
            end = matches[i + 1].start() if i + 1 < len(matches) else len(transcript)
            turn = transcript[start:end].strip()
            if turn:
                turns_by_speaker.append(turn)
        # Se conseguimos turnos suficientes, retornamos eles.
        if len(turns_by_speaker) >= 2:
            # Limita a _TARGET_CHUNKS agrupando turnos pequenos.
            return _coalesce(turns_by_speaker, target=_TARGET_CHUNKS)

    # 2) Paragrafos --- linhas separadas por whitespace duplo
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", transcript) if p.strip()]
    if len(paragraphs) >= 2:
        return _coalesce(paragraphs, target=_TARGET_CHUNKS)

    # 3) Linhas --- ultimo fallback antes de cair em doc unico
    lines = [line.strip() for line in transcript.splitlines() if line.strip()]
    if len(lines) >= 4:
        return _coalesce(lines, target=_TARGET_CHUNKS)

    # 4) Doc unico
    return [transcript.strip()]


def _coalesce(parts: list[str], *, target: int) -> list[str]:
    """Junta partes adjacentes ate ter no maximo ``target`` chunks.

    Garante chunks com ao menos ``_MIN_CHUNK_CHARS`` de tamanho quando possivel.
    """
    if len(parts) <= target:
        # Mesmo assim, garante tamanho minimo por chunk.
        out: list[str] = []
        for p in parts:
            if out and len(out[-1]) < _MIN_CHUNK_CHARS:
                out[-1] = f"{out[-1]} {p}".strip()
            else:
                out.append(p)
        return out

    # Coalescer em janelas uniformes.
    bucket_size = max(1, (len(parts) + target - 1) // target)
    grouped: list[str] = []
    for i in range(0, len(parts), bucket_size):
        chunk = " ".join(parts[i : i + bucket_size]).strip()
        if chunk:
            grouped.append(chunk)
    return grouped


def extract_baseline_terms(transcript: str, *, top_n: int = 10) -> list[BaselineTerm]:
    """Extrai os ``top_n`` termos TF-IDF mais relevantes do transcript.

    Args:
        transcript: transcricao ja redigida (ou bruta, se PII nao se aplicar).
        top_n: numero maximo de termos retornados.

    Returns:
        Lista de ``BaselineTerm`` ordenada por score desc. Lista vazia em
        transcript vazio, ou se o TF-IDF nao conseguiu vocabulario util
        (transcript curtissimo, so stopwords, etc.). Nunca levanta excecao
        --- o baseline eh um *step* opcional e nunca pode derrubar a request.
    """
    if not transcript or not transcript.strip():
        return []
    if top_n <= 0:
        return []

    chunks = _chunk_transcript(transcript)
    if not chunks:
        return []

    try:
        baseline = TfidfBaseline(
            ngram_range=(1, 2),
            max_features=500,
            min_df=1,
            max_df=0.95,
        )
        baseline.fit(chunks)
    except ValueError as exc:
        logger.warning("TF-IDF baseline nao gerou vocabulario util: %s", exc)
        return []

    # Top global (media dos scores ao longo dos chunks). Faz sentido aqui
    # porque cada chunk eh um pseudo-doc independente.
    raw_top = baseline.top_terms(top_n=top_n)

    # Conversao defensiva: TfidfVectorizer normaliza L2 por documento, entao
    # os scores ficam tipicamente em [0, 1]. Capamos em [0, 1] para casar com
    # a constraint do schema (`ge=0.0, le=1.0`) e evitar 502 por validacao.
    out: list[BaselineTerm] = []
    for term, score in raw_top:
        clipped = max(0.0, min(1.0, float(score)))
        out.append(BaselineTerm(term=term, score=clipped))
    return out
