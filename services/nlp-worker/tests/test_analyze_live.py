import os

from fastapi.testclient import TestClient

os.environ["USE_LLM_STUB"] = "true"

from nora_nlp.main import app

client = TestClient(app)

TRANSCRIPT_CHUNK = """\
[Speaker_1] Bom, entao decidimos que vamos migrar o ERP para a nuvem ate o final do Q3.
[Speaker_2] Concordo. Vamos precisar avaliar os fornecedores ainda essa semana.
[Speaker_1] O Carlos vai preparar o levantamento de requisitos e enviar ate sexta.
[Speaker_2] Importante notar que o cliente demonstrou bastante interesse no modulo de RH.
"""


def _make_request(chunk: str, previous=None) -> dict:
    payload = {
        "transcriptChunk": chunk,
        "language": "pt-BR",
    }
    if previous is not None:
        payload["previousHighlights"] = previous
    return payload


def test_analyze_live_returns_valid_response():
    payload = _make_request(TRANSCRIPT_CHUNK)
    resp = client.post("/analyze-live", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    assert "decisions" in body
    assert "nextSteps" in body
    assert "observations" in body
    assert "tasks" in body
    assert "metadata" in body
    assert isinstance(body["decisions"], list)
    assert isinstance(body["nextSteps"], list)
    assert isinstance(body["observations"], list)
    assert isinstance(body["tasks"], list)
    assert body["metadata"]["modelVersion"].startswith("stub")


def test_analyze_live_detects_decision():
    payload = _make_request("[Speaker_1] Decidimos fechar com o fornecedor X.")
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()
    assert len(body["decisions"]) >= 1
    assert any(
        "fechar" in d["text"].lower() or "fornecedor" in d["text"].lower()
        for d in body["decisions"]
    )


def test_analyze_live_detects_task():
    payload = _make_request("[Speaker_1] O Paulo vai enviar a proposta ate sexta-feira.")
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()
    assert len(body["tasks"]) >= 1


def test_analyze_live_detects_observation():
    payload = _make_request("[Speaker_1] E importante notar que o concorrente baixou o preco.")
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()
    assert len(body["observations"]) >= 1


def test_analyze_live_with_previous_highlights_dedup():
    previous = {
        "decisions": [
            {
                "text": "Migrar ERP para a nuvem ate o final do Q3",
                "confidence": 0.85,
                "sourceQuote": "vamos migrar",
            }
        ],
        "nextSteps": [],
        "observations": [],
        "tasks": [],
    }
    payload = _make_request(
        "[Speaker_1] Entao decidimos que vamos migrar o ERP para a nuvem "
        "ate o final do Q3.\n[Speaker_2] Certo.",
        previous=previous,
    )
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()
    decisions_text = [d["text"].lower() for d in body["decisions"]]
    assert not any("migrar" in t and "erp" in t for t in decisions_text)


def test_analyze_live_empty_chunk_returns_empty():
    payload = _make_request("[Speaker_1] Sim.\n[Speaker_2] Hmm.\n[Speaker_1] Ok.")
    resp = client.post("/analyze-live", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body["decisions"], list)


def test_analyze_live_rejects_short_chunk():
    payload = _make_request("muito curto")
    resp = client.post("/analyze-live", json=payload)
    assert resp.status_code == 422


def test_analyze_live_full_transcript():
    payload = _make_request(TRANSCRIPT_CHUNK)
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()

    assert len(body["decisions"]) >= 1, "Expected at least 1 decision"
    assert len(body["tasks"]) >= 1, "Expected at least 1 task (Carlos vai preparar)"
    assert body["metadata"]["processingMillis"] >= 0


def test_analyze_live_source_quote_present():
    payload = _make_request("[Speaker_1] Decidimos contratar 2 desenvolvedores ate marco.")
    resp = client.post("/analyze-live", json=payload)
    body = resp.json()
    for d in body["decisions"]:
        assert "sourceQuote" in d
        assert len(d["sourceQuote"]) >= 3
