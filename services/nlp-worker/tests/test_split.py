"""Tests for the /split endpoint and the split_analyzer (mocked LLM).

Covers: multi-meeting happy path, single meeting, invalid LLM boundaries
corrected server-side, PII never leaking into the preview and the window merge.
"""

from __future__ import annotations

import json
import os
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

os.environ["USE_LLM_STUB"] = "true"

from nora_nlp.main import app
from nora_nlp.models import SplitRequest
from nora_nlp.services import split_analyzer
from nora_nlp.services.split_analyzer import analyze, normalize_segments, redact_lines
from nora_nlp.settings import Settings

client = TestClient(app)

_MULTI_MEETING_TRANSCRIPT = (
    "=== Reuniao de discovery com a Acme ===\n"
    "Carlos: bom dia, vamos falar do ERP.\n"
    "Vendedor: claro, me conta a dor de voces.\n"
    "Carlos: fechamos entao, obrigado a todos.\n"
    "=== Daily do time de produto ===\n"
    "PO: bom dia time, status das tarefas.\n"
    "Dev: terminei o layout ontem.\n"
    "PO: otimo, seguimos.\n"
)

_SINGLE_MEETING_TRANSCRIPT = (
    "Vendedor: bom dia, vamos revisar a proposta.\n"
    "Cliente: bom dia, podemos sim.\n"
    "Vendedor: entao vamos comecar pelo escopo.\n"
)


def _make_settings() -> Settings:
    return Settings(
        llm_provider="openai",
        llm_base_url="https://api.openai.com/v1",
        llm_api_key="sk-test-fake-1234567890",
        llm_model="gpt-4o-mini",
        llm_temperature=0.2,
        use_llm_stub=False,
    )


def _mock_client_returning(payloads: list[dict]) -> MagicMock:
    """LlmClient mock: each chat_structured call returns the next payload."""
    instance = MagicMock()
    instance.chat_structured.side_effect = [(json.dumps(p), 100, 50) for p in payloads]
    return instance


def _assert_contract(body: dict) -> None:
    """Contract invariants: ordered, no overlap, no gap, covering the file."""
    segments = body["segments"]
    assert segments, "esperava ao menos 1 segmento"
    assert segments[0]["startLine"] == 1
    assert segments[-1]["endLine"] == body["totalLines"]
    for i, seg in enumerate(segments):
        assert seg["index"] == i + 1
        assert 1 <= seg["startLine"] <= seg["endLine"] <= body["totalLines"]
        assert 0.0 <= seg["confidence"] <= 1.0
        assert seg["title"]
        if i > 0:
            assert seg["startLine"] == segments[i - 1]["endLine"] + 1


# ---------- Endpoint (deterministic stub, USE_LLM_STUB=true) ----------


def test_split_endpoint_multi_meeting_stub():
    resp = client.post(
        "/split", json={"transcript": _MULTI_MEETING_TRANSCRIPT, "language": "pt-BR"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    _assert_contract(body)
    assert len(body["segments"]) >= 2
    assert body["metadata"]["modelVersion"].startswith("stub")
    assert body["metadata"]["promptVersion"] == "meeting-split-v1"
    assert body["metadata"]["processingMillis"] >= 0


def test_split_endpoint_single_meeting_stub():
    resp = client.post(
        "/split", json={"transcript": _SINGLE_MEETING_TRANSCRIPT, "language": "pt-BR"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    _assert_contract(body)
    assert len(body["segments"]) == 1
    assert body["segments"][0]["startLine"] == 1
    assert body["segments"][0]["endLine"] == body["totalLines"]


def test_split_endpoint_rejects_empty_transcript():
    resp = client.post("/split", json={"transcript": "", "language": "pt-BR"})
    assert resp.status_code == 422


# ---------- split_analyzer with mocked LLM ----------


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_llm_happy_path_multi_meeting(MockClient):
    red_lines, redactions = redact_lines(_MULTI_MEETING_TRANSCRIPT)
    total = len(red_lines)
    MockClient.return_value = _mock_client_returning(
        [
            {
                "segments": [
                    {
                        "title": "Discovery com a Acme",
                        "startLine": 1,
                        "endLine": 4,
                        "confidence": 0.95,
                    },
                    {
                        "title": "Daily do time de produto",
                        "startLine": 5,
                        "endLine": total,
                        "confidence": 0.9,
                    },
                ]
            }
        ]
    )

    req = SplitRequest(transcript=_MULTI_MEETING_TRANSCRIPT, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings(), pii_redactions_applied=redactions)
    body = resp.model_dump(by_alias=True)

    _assert_contract(body)
    assert len(body["segments"]) == 2
    assert body["segments"][0]["title"] == "Discovery com a Acme"
    assert body["segments"][0]["endLine"] == 4
    assert body["segments"][1]["startLine"] == 5
    assert body["metadata"]["modelVersion"] == "openai-gpt-4o-mini"
    assert body["metadata"]["tokensInput"] == 100
    assert body["metadata"]["tokensOutput"] == 50


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_llm_single_meeting_is_valid(MockClient):
    red_lines, _ = redact_lines(_SINGLE_MEETING_TRANSCRIPT)
    total = len(red_lines)
    MockClient.return_value = _mock_client_returning(
        [
            {
                "segments": [
                    {
                        "title": "Revisao de proposta",
                        "startLine": 1,
                        "endLine": total,
                        "confidence": 0.8,
                    }
                ]
            }
        ]
    )

    req = SplitRequest(transcript=_SINGLE_MEETING_TRANSCRIPT, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings())
    body = resp.model_dump(by_alias=True)

    _assert_contract(body)
    assert len(body["segments"]) == 1


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_llm_invalid_boundaries_are_corrected(MockClient):
    """LLM returns garbage (overlap, gap, out of range, unordered): the
    server normalizes into ordered segments, without overlap, covering the
    whole file."""
    red_lines, _ = redact_lines(_MULTI_MEETING_TRANSCRIPT)
    MockClient.return_value = _mock_client_returning(
        [
            {
                "segments": [
                    # unordered + out of range (endLine past the file)
                    {"title": "Daily", "startLine": 6, "endLine": 99, "confidence": 1.5},
                    # gap between 3 and 6 + head of the file uncovered (line 1)
                    {"title": "Discovery", "startLine": 2, "endLine": 3, "confidence": 0.9},
                    # entirely nested inside "Daily" — must disappear
                    {"title": "Fantasma", "startLine": 7, "endLine": 8, "confidence": 0.4},
                ]
            }
        ]
    )

    req = SplitRequest(transcript=_MULTI_MEETING_TRANSCRIPT, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings())
    body = resp.model_dump(by_alias=True)

    _assert_contract(body)
    titles = [s["title"] for s in body["segments"]]
    assert "Discovery" in titles
    assert "Daily" in titles
    assert "Fantasma" not in titles
    # the gap (4-5) became an extension of the previous segment; confidence clamped.
    discovery = next(s for s in body["segments"] if s["title"] == "Discovery")
    daily = next(s for s in body["segments"] if s["title"] == "Daily")
    assert discovery["startLine"] == 1  # head of the file covered
    assert discovery["endLine"] == daily["startLine"] - 1
    assert daily["endLine"] == body["totalLines"]
    assert daily["confidence"] == 1.0


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_llm_empty_response_falls_back_to_single_segment(MockClient):
    red_lines, _ = redact_lines(_SINGLE_MEETING_TRANSCRIPT)
    MockClient.return_value = _mock_client_returning([{"segments": []}])

    req = SplitRequest(transcript=_SINGLE_MEETING_TRANSCRIPT, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings())
    body = resp.model_dump(by_alias=True)

    _assert_contract(body)
    assert len(body["segments"]) == 1


# ---------- PII never leaks (ADR 0012) ----------

_PII_TRANSCRIPT = (
    "=== Reuniao comercial ===\n"
    "Cliente: meu email e joao.silva@example.com e meu telefone (11) 98765-4321.\n"
    "Vendedor: anotado, e o CPF para o contrato?\n"
    "Cliente: e 111.444.777-35, em nome de Joao da Silva.\n"
    "=== Alinhamento interno ===\n"
    "PO: time, vamos revisar o backlog da semana.\n"
)


def test_split_preview_never_leaks_pii_stub():
    resp = client.post("/split", json={"transcript": _PII_TRANSCRIPT, "language": "pt-BR"})
    assert resp.status_code == 200, resp.text
    raw_body = resp.text

    assert "joao.silva@example.com" not in raw_body
    assert "98765-4321" not in raw_body
    assert "111.444.777-35" not in raw_body
    assert "Joao da Silva" not in raw_body

    body = resp.json()
    previews = " ".join(s["preview"] for s in body["segments"])
    assert "[[EMAIL_" in previews or "[[PHONE_" in previews or "[[CPF_" in previews
    assert body["metadata"]["piiRedactionsApplied"] >= 4


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_llm_prompt_and_preview_receive_redacted_text(MockClient):
    """The numbered text sent to the LLM and the response preview are already redacted."""
    red_lines, redactions = redact_lines(_PII_TRANSCRIPT)
    total = len(red_lines)
    mock_instance = _mock_client_returning(
        [{"segments": [{"title": "Reuniao", "startLine": 1, "endLine": total, "confidence": 0.9}]}]
    )
    MockClient.return_value = mock_instance

    req = SplitRequest(transcript=_PII_TRANSCRIPT, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings(), pii_redactions_applied=redactions)

    user_prompt = mock_instance.chat_structured.call_args.kwargs["user_prompt"]
    for leaked in ("joao.silva@example.com", "111.444.777-35", "98765-4321"):
        assert leaked not in user_prompt
        assert leaked not in resp.segments[0].preview
    assert "[[EMAIL_1]]" in user_prompt
    assert resp.metadata.pii_redactions_applied == redactions


# ---------- Intra-line redaction (stable line numbers) ----------


def test_redact_lines_preserves_line_count():
    red_lines, count = redact_lines(_PII_TRANSCRIPT)
    assert len(red_lines) == _PII_TRANSCRIPT.count("\n") + 1
    assert count >= 4
    # each redacted line stays ONE line (no internal \n).
    assert all("\n" not in line for line in red_lines)


def test_redact_lines_keeps_phone_split_across_lines_intra_line():
    """A phone with \\s in the regex could match across lines in the global
    shield; line by line the number of lines never changes."""
    text = "contato (11)\n98765 4321 final\noutra linha"
    red_lines, _ = redact_lines(text)
    assert len(red_lines) == 3


def test_redact_lines_normalizes_crlf_and_cr_to_match_frontend():
    """The client-side slicing normalizes CRLF/CR->LF before cutting by line
    (sliceFileLines in apps/web/.../upload/page.tsx). The worker has to number
    the SAME lines, otherwise the cuts come out shifted. Guarantees count
    parity for LF, CRLF (Windows) and lone-CR (classic Mac)."""
    lf = "reuniao um\nlinha dois\nreuniao dois"
    crlf = "reuniao um\r\nlinha dois\r\nreuniao dois"
    cr = "reuniao um\rlinha dois\rreuniao dois"

    base_lines, _ = redact_lines(lf)
    assert len(base_lines) == 3
    for variant in (crlf, cr):
        red_lines, _ = redact_lines(variant)
        # same count as LF...
        assert len(red_lines) == 3
        # ...and no residual \r leaking into the preview.
        assert all("\r" not in line for line in red_lines)
        assert red_lines == base_lines


def test_redact_lines_trailing_newline_matches_frontend_split():
    """A file ending in a newline yields a trailing "" element both in the
    worker split and in the front's split('\\n') after normalizing — parity
    at the last-line boundary."""
    text = "reuniao um\r\nlinha dois\r\n"
    red_lines, _ = redact_lines(text)
    # "a\nb\n".split("\n") == ["a", "b", ""] (3 elements), same on the front.
    assert len(red_lines) == 3
    assert red_lines[-1] == ""


# ---------- Windows + merge ----------


@patch("nora_nlp.services.split_analyzer.LlmClient")
def test_split_windows_merge_boundaries(MockClient, monkeypatch):
    """Forces 2+ windows with a tiny budget and checks the merge: the window's
    last truncated segment is re-analyzed in the next one; a single-segment
    window is merged with the first boundary of the next."""
    transcript = "\n".join(
        ["=== Reuniao A ==="]
        + [f"A fala numero {i} da primeira reuniao" for i in range(1, 10)]
        + ["=== Reuniao B ==="]
        + [f"B fala numero {i} da segunda reuniao" for i in range(1, 10)]
    )
    red_lines, _ = redact_lines(transcript)
    total = len(red_lines)  # 20 lines

    # Small budget: ~6 lines per window.
    monkeypatch.setattr(split_analyzer, "_WINDOW_CHAR_BUDGET", 220)

    def respond(**kwargs):
        """Real boundary oracle: meeting A = lines 1-10, B = 11-20."""
        prompt = kwargs["user_prompt"]
        # extract the window range from the numbered lines present
        lines = [
            int(line.split("|", 1)[0])
            for line in prompt.splitlines()
            if "|" in line and line.split("|", 1)[0].strip().isdigit()
        ]
        first, last = min(lines), max(lines)
        segs = []
        if first <= 10:
            segs.append(
                {
                    "title": "Reuniao A",
                    "startLine": first,
                    "endLine": min(10, last),
                    "confidence": 0.9,
                }
            )
        if last >= 11:
            segs.append(
                {
                    "title": "Reuniao B",
                    "startLine": max(11, first),
                    "endLine": last,
                    "confidence": 0.9,
                }
            )
        return (json.dumps({"segments": segs}), 10, 5)

    instance = MagicMock()
    instance.chat_structured.side_effect = respond
    MockClient.return_value = instance

    req = SplitRequest(transcript=transcript, language="pt-BR")
    resp = analyze(req, red_lines, _make_settings())
    body = resp.model_dump(by_alias=True)

    assert instance.chat_structured.call_count >= 2, "esperava multiplas janelas"
    _assert_contract(body)
    assert len(body["segments"]) == 2
    assert body["segments"][0]["title"] == "Reuniao A"
    assert body["segments"][0]["endLine"] == 10
    assert body["segments"][1]["startLine"] == 11
    assert body["segments"][1]["endLine"] == total


# ---------- normalize_segments (unit) ----------


def test_normalize_segments_empty_input_yields_full_file_segment():
    result = normalize_segments([], 7)
    assert len(result) == 1
    assert result[0]["startLine"] == 1
    assert result[0]["endLine"] == 7
    assert result[0]["confidence"] == 0.0


def test_normalize_segments_discards_garbage_entries():
    result = normalize_segments(
        [
            {"title": "ok", "startLine": 1, "endLine": 3, "confidence": 0.5},
            {"title": "lixo", "startLine": "abc", "endLine": None, "confidence": 0.5},
        ],
        5,
    )
    assert len(result) == 1
    assert result[0]["title"] == "ok"
    assert result[0]["endLine"] == 5  # extended tail
