"""Split Analyzer: deteccao de fronteiras entre reunioes num arquivo unico.

O usuario sobe UM arquivo .txt com varias reunioes concatenadas; o worker
propoe os pontos de corte (linhas 1-based) e o fatiamento real acontece
client-side sobre o arquivo ORIGINAL. Por isso os numeros de linha do texto
redigido precisam corresponder 1:1 aos do original — ver ``redact_lines``.

Pipeline:
  1. PII Shield LINHA A LINHA (``redact_lines``) — redacao intra-linha.
  2. Numera as linhas (1-based, formato ``N| texto``) e monta janelas.
  3. LLM (gpt-4o-mini via cliente agnostico, ADR 0004) com JSON Schema strict
     (ADR 0003) por janela; fallback para JSON mode.
  4. Mescla as fronteiras das janelas (estrategia documentada em ``analyze``).
  5. Normalizacao server-side (``normalize_segments``) — nunca confia no LLM.
  6. Previews redigidos (~200 chars) + metadata de execucao.

Estrategia de janelas + merge (transcricao maior que o budget de contexto):
  - Cada janela cobre linhas consecutivas ate ``_WINDOW_CHAR_BUDGET`` chars
    (~60k tokens — folga ampla dentro dos 128k do gpt-4o-mini, descontando
    prompt e saida).
  - Se a janela detectou >= 2 segmentos, o ULTIMO esta provavelmente truncado
    pelo limite da janela: ele e descartado e a proxima janela COMECA no
    ``startLine`` dele — ou seja, o trecho truncado e re-analisado por inteiro
    (este e o overlap entre janelas; as fronteiras aceitas sao sempre as da
    janela que viu o segmento completo).
  - Se a janela detectou 1 segmento so (nenhuma fronteira interna), a reuniao
    provavelmente continua na proxima janela: o segmento fica "pendente" e e
    fundido com o primeiro segmento da janela seguinte (titulo da janela que
    viu o INICIO da reuniao prevalece; confidence vira o minimo das duas).
  - Progresso garantido: a proxima janela sempre comeca depois do inicio da
    atual; em caso degenerado cai para ``fim da janela + 1``.

Cap pragmatico: arquivos ate 1MB (mesmo limite do /analyze).
"""

from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path
from typing import Any

from ..clients.llm import LlmClient
from ..models import SplitRequest, SplitResponse, SplitSegment
from ..settings import Settings
from .pii_shield import redact as pii_redact

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

PROMPT_VERSION = "meeting-split-v1"

# Budget de chars por janela (~60k tokens a ~4 chars/token). 1MB de transcript
# vira no maximo ~5 janelas.
_WINDOW_CHAR_BUDGET = 240_000

# Tamanho do preview redigido devolvido por segmento.
_PREVIEW_MAX_CHARS = 200

_TITLE_MAX_CHARS = 120

_FALLBACK_TITLE = "Transcricao completa"

_WHITESPACE_RE = re.compile(r"\s+")


# --------------------------------------------------------------------------- #
# PII Shield linha a linha
# --------------------------------------------------------------------------- #


def redact_lines(transcript: str) -> tuple[list[str], int]:
    """Aplica o PII Shield linha a linha e retorna (linhas redigidas, contagem).

    O shield global (``pii_shield.redact`` sobre o texto inteiro) pode casar
    padroes ATRAVESSANDO ``\\n`` (telefone/CPF com ``\\s`` no regex, nomes
    compostos quebrados em duas linhas), o que removeria quebras de linha e
    quebraria o mapeamento 1:1 entre linhas do original e do texto redigido.

    Redigir linha a linha garante redacao intra-linha: cada placeholder
    substitui texto dentro da MESMA linha, entao ``startLine``/``endLine``
    calculados sobre o texto redigido valem para o arquivo original — requisito
    do fatiamento client-side. Custo: a numeracao dos placeholders reinicia a
    cada linha (irrelevante aqui: o preview nao precisa de dedup global).
    """
    lines = transcript.split("\n")
    redacted: list[str] = []
    count = 0
    for line in lines:
        out = pii_redact(line)
        redacted.append(out.redacted_text)
        count += len(out.redactions)
    return redacted, count


# --------------------------------------------------------------------------- #
# Prompt helpers (mesmo padrao do llm_analyzer / live_analyzer)
# --------------------------------------------------------------------------- #


def _load_prompt() -> tuple[str, str]:
    path = PROMPTS_DIR / f"{PROMPT_VERSION}.md"
    if not path.exists():
        raise FileNotFoundError(f"Prompt nao encontrado: {path}")

    content = path.read_text(encoding="utf-8")

    system_match = re.search(r"##\s*SYSTEM\s*\n(.*?)(?=\n##\s*USER)", content, re.DOTALL)
    user_match = re.search(r"##\s*USER\s*\n(.*)", content, re.DOTALL)

    if not system_match or not user_match:
        raise ValueError(f"Prompt {PROMPT_VERSION}.md deve conter secoes ## SYSTEM e ## USER")

    return system_match.group(1).strip(), user_match.group(1).strip()


def _escape_placeholders(value: str) -> str:
    """Neutraliza placeholders `{{x}}` vindos do usuario (anti prompt-template
    injection)."""
    if "{{" not in value:
        return value
    return value.replace("{{", "{ {").replace("}}", "} }")


def _render_template(template: str, **variables: str) -> str:
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", _escape_placeholders(value))
    return result


def _build_json_schema_for_split() -> dict[str, Any]:
    """JSON Schema strict da saida do LLM (apenas fronteiras; preview/index sao
    calculados server-side sobre o texto redigido)."""
    return {
        "type": "object",
        "properties": {
            "segments": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "startLine": {"type": "integer"},
                        "endLine": {"type": "integer"},
                        "confidence": {"type": "number"},
                    },
                    "required": ["title", "startLine", "endLine", "confidence"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["segments"],
        "additionalProperties": False,
    }


# --------------------------------------------------------------------------- #
# Validacao server-side (nao confiar no LLM)
# --------------------------------------------------------------------------- #


def _clamp_window_segments(
    raw: list[dict[str, Any]], window_start: int, window_end: int
) -> list[dict[str, Any]]:
    """Sanitiza segmentos de UMA janela: ints validos, clampados ao range da
    janela, ordenados por startLine. Lixo (start > end apos clamp) e descartado.
    """
    cleaned: list[dict[str, Any]] = []
    for seg in raw:
        try:
            start = int(seg.get("startLine"))
            end = int(seg.get("endLine"))
        except (TypeError, ValueError):
            continue
        start = min(max(start, window_start), window_end)
        end = min(max(end, start), window_end)
        try:
            confidence = float(seg.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        confidence = min(max(confidence, 0.0), 1.0)
        title = str(seg.get("title") or "").strip()[:_TITLE_MAX_CHARS]
        cleaned.append(
            {"title": title, "startLine": start, "endLine": end, "confidence": confidence}
        )
    cleaned.sort(key=lambda s: (s["startLine"], s["endLine"]))
    return cleaned


def normalize_segments(raw: list[dict[str, Any]], total_lines: int) -> list[dict[str, Any]]:
    """Normaliza a lista global de segmentos para o contrato do endpoint:

    - ordenados por ``startLine``;
    - sem sobreposicao (overlap e cortado: o proximo passa a comecar depois do
      fim do anterior; segmento engolido por inteiro e descartado);
    - dentro de ``[1, total_lines]``;
    - cobrindo o arquivo inteiro: buracos viram extensao do segmento ANTERIOR
      (e o primeiro segmento e estendido ate a linha 1; o ultimo ate o fim);
    - lista vazia vira 1 segmento unico cobrindo o arquivo (fallback).

    1 segmento so tambem e resposta valida (arquivo de reuniao unica).
    """
    cleaned: list[dict[str, Any]] = []
    for seg in raw:
        try:
            start = int(seg.get("startLine"))
            end = int(seg.get("endLine"))
        except (TypeError, ValueError):
            continue
        start = min(max(start, 1), total_lines)
        end = min(max(end, start), total_lines)
        try:
            confidence = float(seg.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        cleaned.append(
            {
                "title": str(seg.get("title") or "").strip()[:_TITLE_MAX_CHARS],
                "startLine": start,
                "endLine": end,
                "confidence": min(max(confidence, 0.0), 1.0),
            }
        )

    cleaned.sort(key=lambda s: (s["startLine"], s["endLine"]))

    result: list[dict[str, Any]] = []
    for seg in cleaned:
        if not result:
            seg["startLine"] = 1  # cabeca do arquivo sempre coberta
            result.append(seg)
            continue
        prev = result[-1]
        if seg["startLine"] <= prev["endLine"]:
            seg["startLine"] = prev["endLine"] + 1
            if seg["startLine"] > seg["endLine"]:
                continue  # engolido por inteiro pelo anterior
        elif seg["startLine"] > prev["endLine"] + 1:
            prev["endLine"] = seg["startLine"] - 1  # buraco vira extensao do anterior
        result.append(seg)

    if not result:
        result = [
            {
                "title": _FALLBACK_TITLE,
                "startLine": 1,
                "endLine": total_lines,
                "confidence": 0.0,
            }
        ]

    result[-1]["endLine"] = total_lines  # cauda do arquivo sempre coberta
    return result


def build_preview(redacted_lines: list[str], start_line: int, end_line: int) -> str:
    """Primeiros ~200 chars do segmento JA REDIGIDO, em linha unica."""
    text = "\n".join(redacted_lines[start_line - 1 : end_line]).strip()
    text = _WHITESPACE_RE.sub(" ", text)
    return text[:_PREVIEW_MAX_CHARS]


def assemble_segments(
    raw_segments: list[dict[str, Any]],
    redacted_lines: list[str],
) -> list[SplitSegment]:
    """Normaliza + materializa os ``SplitSegment`` finais (index 1-based,
    preview redigido, titulo com fallback)."""
    total_lines = len(redacted_lines)
    normalized = normalize_segments(raw_segments, total_lines)
    segments: list[SplitSegment] = []
    for i, seg in enumerate(normalized, start=1):
        title = seg["title"] or f"Reuniao {i}"
        preview = build_preview(redacted_lines, seg["startLine"], seg["endLine"])
        segments.append(
            SplitSegment(
                index=i,
                title=title,
                startLine=seg["startLine"],
                endLine=seg["endLine"],
                confidence=seg["confidence"],
                preview=preview,
            )
        )
    return segments


# --------------------------------------------------------------------------- #
# Pipeline principal
# --------------------------------------------------------------------------- #


def _window_end(numbered: list[str], start_line: int, char_budget: int) -> int:
    """Ultima linha (1-based, inclusiva) da janela que comeca em ``start_line``.

    Garante pelo menos 1 linha por janela mesmo que a linha sozinha estoure o
    budget (linha patologicamente longa nao trava o loop).
    """
    consumed = 0
    end = start_line - 1  # nenhuma linha consumida ainda
    for i in range(start_line - 1, len(numbered)):
        line_cost = len(numbered[i]) + 1  # +1 pelo \n
        if consumed + line_cost > char_budget and end >= start_line:
            break
        consumed += line_cost
        end = i + 1
    return end


def analyze(
    req: SplitRequest,
    redacted_lines: list[str],
    settings: Settings,
    *,
    pii_redactions_applied: int = 0,
) -> SplitResponse:
    """Detecta fronteiras de reunioes via LLM, em janelas se necessario."""
    started = time.monotonic()

    client = LlmClient(settings)
    system_prompt, user_template = _load_prompt()
    json_schema = _build_json_schema_for_split()

    total_lines = len(redacted_lines)
    numbered = [f"{i + 1}| {line}" for i, line in enumerate(redacted_lines)]

    raw_segments: list[dict[str, Any]] = []
    pending: dict[str, Any] | None = None  # segmento aberto da janela anterior
    tokens_in_total = 0
    tokens_out_total = 0

    pos = 1
    while pos <= total_lines:
        end = _window_end(numbered, pos, _WINDOW_CHAR_BUDGET)
        window_text = "\n".join(numbered[pos - 1 : end])
        user_prompt = _render_template(
            user_template,
            language=req.language,
            first_line=str(pos),
            last_line=str(end),
            numbered_transcript=window_text,
        )

        try:
            raw_json, tokens_in, tokens_out = client.chat_structured(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                json_schema=json_schema,
                schema_name="meeting_split",
                temperature=0.1,
            )
        except Exception as exc:
            logger.warning("Structured output falhou no split, fallback JSON mode: %s", exc)
            raw_json, tokens_in, tokens_out = client.chat_json(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                temperature=0.1,
            )
        tokens_in_total += tokens_in
        tokens_out_total += tokens_out

        # NAO logar raw_json: ADR 0012 (PII never logged).
        logger.debug("Split LLM raw response: %d chars (janela %d-%d)", len(raw_json), pos, end)

        parsed = json.loads(raw_json)
        win = _clamp_window_segments(parsed.get("segments") or [], pos, end)

        # Funde o segmento pendente (janela anterior sem fronteira interna)
        # com o primeiro desta janela: mesma reuniao continuando.
        if pending is not None:
            if win:
                first = win[0]
                first["startLine"] = pending["startLine"]
                first["title"] = pending["title"] or first["title"]
                first["confidence"] = min(pending["confidence"], first["confidence"])
            else:
                raw_segments.append(pending)
            pending = None

        if end >= total_lines:
            raw_segments.extend(win)
            break

        if len(win) >= 2:
            # Ultimo segmento provavelmente truncado pela janela: re-analisa
            # por inteiro na proxima janela (overlap controlado).
            tail = win[-1]
            raw_segments.extend(win[:-1])
            pos = tail["startLine"] if tail["startLine"] > pos else end + 1
        elif len(win) == 1:
            pending = win[0]
            pos = end + 1
        else:
            # LLM nao devolveu nada util: trata a janela como reuniao unica
            # sem titulo (normalize/fallback cuidam do resto).
            pending = {"title": "", "startLine": pos, "endLine": end, "confidence": 0.0}
            pos = end + 1

    if pending is not None:
        raw_segments.append(pending)

    segments = assemble_segments(raw_segments, redacted_lines)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    return SplitResponse.model_validate(
        {
            "segments": [s.model_dump(by_alias=True) for s in segments],
            "totalLines": total_lines,
            "metadata": {
                "modelVersion": f"{settings.llm_provider}-{settings.llm_model}",
                "promptVersion": PROMPT_VERSION,
                "tokensInput": tokens_in_total,
                "tokensOutput": tokens_out_total,
                "processingMillis": elapsed_ms,
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )
