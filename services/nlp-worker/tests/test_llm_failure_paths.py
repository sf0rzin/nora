"""What the worker does when the provider answers badly, and what it writes down while doing it.

Three branches of `routers/analyze.py` had no test at all: `LLM_RESPONSE_INVALID`,
`LLM_CONFIG_INVALID`, and the response that is not JSON. That absence is why two of them were
wrong, and in opposite directions.

  * A response that parses and violates the schema was reported correctly, but the LOG LINE was
    `%s` of the `ValidationError` -- and pydantic v2 puts `input_value=` in that string. For a
    field missing at the model level the input is the whole response dictionary, so the line
    three above it in `llm_analyzer` ("Do NOT log raw_json: it may contain residual PII") was
    undone by the exception handler. ADR 0012.

  * A response that does not parse at all was reported as 503 `LLM_CONFIG_INVALID` with the
    message "Expecting value: line 1 column 1 (char 0)", because `json.JSONDecodeError` is a
    `ValueError` and the generic branch caught it. Whoever read that log was sent to inspect an
    env file that was correct. ADR 0004 makes the provider swappable and the OpenAI-compatible
    endpoints people point this at routinely ignore `response_format`, so this is the ordinary
    failure of a documented configuration.

The PII assertions here use a name on NEITHER of the shield's lists on purpose: it is planted in
the model's answer, not in the transcript, so nothing upstream can have removed it and any
appearance in the log came from the handler.
"""

from __future__ import annotations

import json
import logging
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from nora_nlp.main import app
from nora_nlp.settings import Settings, get_settings

client = TestClient(app)

# Off both name lists, so the shield does not remove it and its presence is unambiguous.
PLANTED_NAME = "Wanderleia Kranz"
PLANTED_QUOTE = f"{PLANTED_NAME} confirmou o prazo com o cliente."

_TRANSCRIPT = (
    "[Speaker_1] Bom dia, vamos revisar o escopo do rollout.\n"
    "[Speaker_2] O lote reduzido fica decidido para a primeira carga.\n"
    "[Speaker_1] Fechamos assim.\n"
)

_ANALYZE_BODY = {
    "meetingId": "test-meeting-failure-paths",
    "tenantId": "00000000-0000-4000-8000-000000000001",
    "language": "pt-BR",
    "transcript": _TRANSCRIPT,
    "tenantContext": {
        "companyName": "Northwind",
        "industry": "ERP",
        "valueProposition": "ERP para industria",
        "products": [],
        "competitors": [],
        "objectionHandling": [],
        "glossary": [],
    },
}

_LIVE_BODY = {
    "transcriptChunk": _TRANSCRIPT,
    "language": "pt-BR",
}

_SPLIT_BODY = {
    "transcript": _TRANSCRIPT,
    "language": "pt-BR",
}

# A response that parses and is not the contract, per route.
#
# /analyze and /analyze-live validate through a pydantic model, so an object with none of the
# required fields fails at the MODEL level -- which is the case where pydantic hands the whole
# dictionary as `input` and the old log line printed it.
#
# /split validates by hand, and its shape fault is different in kind: a bare JSON array at the
# top level, which is what an OpenAI-compatible endpoint ignoring `response_format` produces. An
# OBJECT with no `segments` key is not a fault there -- it reads as a window with no boundary,
# which is a legitimate answer -- so the array is the case worth pinning.
_OFF_CONTRACT_OBJECT = {"sourceQuote": PLANTED_QUOTE, "accountName": PLANTED_NAME}
_OFF_CONTRACT_BY_ROUTE = {
    "/analyze": json.dumps(_OFF_CONTRACT_OBJECT),
    "/analyze-live": json.dumps(_OFF_CONTRACT_OBJECT),
    "/split": json.dumps([{"startLine": 1, "title": PLANTED_QUOTE}]),
}

_NOT_JSON_RESPONSE = f"Claro! Aqui esta a analise da reuniao: {PLANTED_QUOTE}"

_ROUTES = (
    ("/analyze", _ANALYZE_BODY, "nora_nlp.services.llm_analyzer.LlmClient"),
    ("/analyze-live", _LIVE_BODY, "nora_nlp.services.live_analyzer.LlmClient"),
    ("/split", _SPLIT_BODY, "nora_nlp.services.split_analyzer.LlmClient"),
)


@pytest.fixture(autouse=True)
def _real_llm_mode():
    """The failure branches only exist on the non-stub path."""
    app.dependency_overrides[get_settings] = lambda: Settings(
        llm_api_key="sk-test-fake-1234567890",
        use_llm_stub=False,
    )
    yield
    app.dependency_overrides.pop(get_settings, None)


def _mock_returning(raw: str) -> MagicMock:
    instance = MagicMock()
    instance.chat_structured.return_value = (raw, 100, 50)
    instance.chat_json.return_value = (raw, 100, 50)
    return instance


@pytest.mark.parametrize("route,body,target", _ROUTES, ids=lambda v: str(v))
def test_an_off_contract_response_is_a_bad_gateway_and_says_so(route, body, target) -> None:
    with patch(target) as MockClient:
        MockClient.return_value = _mock_returning(_OFF_CONTRACT_BY_ROUTE[route])
        resp = client.post(route, json=body)

    assert resp.status_code == 502, resp.text
    assert resp.json()["detail"]["code"] == "LLM_RESPONSE_INVALID"


@pytest.mark.parametrize("route,body,target", _ROUTES, ids=lambda v: str(v))
def test_a_response_that_is_not_json_is_a_bad_gateway_and_not_a_config_error(
    route, body, target
) -> None:
    """The classification, which is the whole finding.

    `json.JSONDecodeError` inherits from `ValueError`, so without a branch of its own it lands
    in the one that means "your LLM_* environment is wrong" -- 503, and a message that is the
    parser's coordinates. Nothing about the worker's configuration is wrong when a model answers
    in prose, and 503 additionally says "unavailable", which it is not.
    """
    with patch(target) as MockClient:
        MockClient.return_value = _mock_returning(_NOT_JSON_RESPONSE)
        resp = client.post(route, json=body)

    assert resp.status_code == 502, resp.text
    detail = resp.json()["detail"]
    assert detail["code"] == "LLM_RESPONSE_INVALID"
    # And the parser's own text does not become the client-facing message.
    assert "Expecting value" not in detail["message"]


@pytest.mark.parametrize(
    "route,body,target,raw",
    [
        (route, body, target, payload)
        for route, body, target in _ROUTES
        for payload in (_OFF_CONTRACT_BY_ROUTE[route], _NOT_JSON_RESPONSE)
    ],
    ids=lambda v: str(v)[:32],
)
def test_the_rejected_response_never_reaches_the_log(route, body, target, raw, caplog) -> None:
    """Nothing the model wrote may appear in what the worker writes down.

    Asserted on the whole captured log rather than on one record: the value can arrive through
    the message, through an argument, or through a traceback, and the finding was about the
    first of those only because that is where it happened to be.

    The name is planted in the RESPONSE, so the shield never saw it and cannot be the reason it
    is absent. If this fails, read the log line -- it is a live ADR 0012 violation, not a
    formatting preference.
    """
    with caplog.at_level(logging.DEBUG), patch(target) as MockClient:
        MockClient.return_value = _mock_returning(raw)
        resp = client.post(route, json=body)

    assert resp.status_code == 502, resp.text
    for token in ("Wanderleia", "Kranz", "confirmou o prazo"):
        assert token not in caplog.text, (
            f"{token!r} from the model's response reached the log:\n{caplog.text}"
        )
