"""Every route to the provider goes through the shield, including the ones taken on failure.

ADR 0012 puts the shield at the last gate before every provider call. A fix to the shield is
worth exactly as much as the coverage of that gate, so this file asserts the coverage rather
than describing it.

Three things are checked, each of which has a plausible way of going wrong:

1. **The fallback carries the redacted prompt.** `chat_structured` raising drops the caller to
   `chat_json`. If that path rebuilt the prompt from the request instead of reusing the string
   already built, the strict-schema call would be shielded and the JSON-mode call would not --
   and it only happens when the first call fails, which is the least-watched path there is.
2. **Retries cannot reintroduce raw text.** They are the OpenAI SDK's (`max_retries=2`), which
   resends the same request body, so this is asserted as "the worker holds no retry of its own"
   rather than left as an assumption about somebody else's library.
3. **There is no embeddings path.** Not "the embeddings path is shielded" -- there is no
   embeddings call in the worker at all, and an absence is worth pinning so that adding one has
   to come past this test.
"""

from __future__ import annotations

import inspect
import json
import re
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from nora_nlp.clients import llm as llm_client_module
from nora_nlp.main import app
from nora_nlp.services import live_analyzer, llm_analyzer, split_analyzer
from nora_nlp.settings import Settings, get_settings

# A name on neither of the shield's lists, so only the shield can remove it, plus a CPF and an
# e-mail. If any of these reaches a prompt the gate is not where ADR 0012 says it is.
RAW_NAME = "Wanderleia Kranz"
RAW_CPF = "529.982.247-25"
RAW_EMAIL = "wanderleia.kranz@northwind.example"
TRANSCRIPT = (
    f"[Speaker_1] {RAW_NAME} apresentou o plano de rollout do Protheus.\n"
    f"[Speaker_2] O CPF dela e {RAW_CPF} e o e-mail {RAW_EMAIL}.\n"
    "[Speaker_1] Fechamos o escopo ate sexta.\n"
)

_FAKE_ANALYSIS = {
    # MeetingAnalysisV1 requires at least 30 characters here.
    "summary": "Resumo sintetico da reuniao para efeito de teste.",
    "decisions": [],
    "actionItems": [],
    "risks": [],
    "opportunities": [],
    "sentimentOverall": "NEUTRAL",
    "topics": [],
    "participants": [],
    "productivity": None,
    "customerConfidence": None,
}

_FAKE_LIVE = {"decisions": [], "nextSteps": [], "observations": [], "tasks": []}


def _live_settings() -> Settings:
    """Settings with the stub OFF, so the request actually reaches a provider client."""
    return Settings(use_llm_stub=False, llm_api_key="test-key")


def _client_that_fails_structured(payload: dict) -> MagicMock:
    """A provider client whose strict-schema call always fails, forcing the fallback."""
    instance = MagicMock()
    instance.model = "gpt-4o-mini"
    instance.provider = "openai"
    instance.chat_structured.side_effect = RuntimeError("structured output not supported")
    instance.chat_json.return_value = (json.dumps(payload), 100, 50)
    return instance


def _prompts_seen(instance: MagicMock) -> list[str]:
    calls = list(instance.chat_structured.call_args_list) + list(instance.chat_json.call_args_list)
    assert calls, "no provider call was made; the test proves nothing"
    return [c.kwargs["system_prompt"] + "\n" + c.kwargs["user_prompt"] for c in calls]


def _assert_clean(prompts: list[str], where: str) -> None:
    for prompt in prompts:
        for secret in (RAW_NAME, RAW_CPF, RAW_EMAIL):
            assert secret not in prompt, f"{secret!r} reached the provider via {where}"
        assert "[[PERSON_NAME_" in prompt, (
            f"{where}: nothing was redacted at all, so this assertion proves nothing"
        )


@patch("nora_nlp.services.llm_analyzer.LlmClient")
def test_the_json_fallback_sends_the_shielded_prompt(MockClient) -> None:
    instance = _client_that_fails_structured(_FAKE_ANALYSIS)
    MockClient.return_value = instance

    app.dependency_overrides[get_settings] = _live_settings
    try:
        resp = TestClient(app).post(
            "/analyze",
            json={
                "meetingId": "m-1",
                "tenantId": "t-1",
                "transcript": TRANSCRIPT,
                "language": "pt-BR",
                "tenantContext": {
                    "companyName": "Northwind",
                    "industry": "Fintech",
                    "valueProposition": "Conciliacao B2B",
                },
            },
        )
    finally:
        app.dependency_overrides.pop(get_settings, None)
    assert resp.status_code == 200, resp.text

    instance.chat_structured.assert_called_once()
    instance.chat_json.assert_called_once()
    _assert_clean(_prompts_seen(instance), "/analyze")

    # The fallback must reuse the prompt, not rebuild it. Equality is the strongest form of
    # "it cannot have been derived from the raw request a second time".
    structured = instance.chat_structured.call_args.kwargs["user_prompt"]
    fallback = instance.chat_json.call_args.kwargs["user_prompt"]
    assert structured == fallback


@patch("nora_nlp.services.live_analyzer.LlmClient")
def test_the_live_json_fallback_sends_the_shielded_prompt(MockClient) -> None:
    instance = _client_that_fails_structured(_FAKE_LIVE)
    MockClient.return_value = instance

    app.dependency_overrides[get_settings] = _live_settings
    try:
        resp = TestClient(app).post(
            "/analyze-live",
            json={"transcriptChunk": TRANSCRIPT, "language": "pt-BR"},
        )
    finally:
        app.dependency_overrides.pop(get_settings, None)
    assert resp.status_code == 200, resp.text

    instance.chat_json.assert_called_once()
    _assert_clean(_prompts_seen(instance), "/analyze-live")
    assert (
        instance.chat_structured.call_args.kwargs["user_prompt"]
        == instance.chat_json.call_args.kwargs["user_prompt"]
    )


@pytest.mark.parametrize(
    "module",
    [llm_analyzer, live_analyzer, split_analyzer],
    ids=lambda m: m.__name__.rsplit(".", 1)[-1],
)
def test_the_fallback_reuses_the_prompt_variable(module) -> None:
    """Source-level companion to the two tests above, for the paths they cannot both cover.

    `split_analyzer` loops over windows and would need a much larger fixture to drive through
    the API; the property is the same one, and it is cheap to state directly: whatever string is
    handed to `chat_structured` is the string handed to `chat_json`.
    """
    source = inspect.getsource(module)
    structured = re.findall(r"chat_structured\((.*?)\n\s*\)", source, re.S)
    fallback = re.findall(r"chat_json\((.*?)\n\s*\)", source, re.S)
    assert structured and fallback, f"{module.__name__}: no provider call found"
    for block in structured + fallback:
        assert "user_prompt=user_prompt" in block, (
            f"{module.__name__} builds a provider prompt from something other than the "
            f"`user_prompt` the shield produced:\n{block}"
        )


def test_the_worker_holds_no_retry_of_its_own() -> None:
    """Retries are the SDK's, on an already-built request body, so they resend redacted text.

    If a retry ever moves into the worker it will rebuild something, and that is the moment this
    reasoning stops holding.
    """
    source = inspect.getsource(llm_client_module)
    assert "max_retries=2" in source
    worker_src = Path(llm_client_module.__file__).parent.parent
    # An import or a decorator, not the word: `llm.py`'s own comment says "exponential backoff"
    # and matching prose made this fail on the file it exists to bless.
    retry_library = re.compile(r"(?m)^\s*(?:from|import)\s+(?:tenacity|backoff)\b|^\s*@retry\b")
    offenders = [
        str(path)
        for path in worker_src.rglob("*.py")
        if retry_library.search(path.read_text(encoding="utf-8"))
    ]
    assert not offenders, f"a retry library appeared in the worker: {offenders}"


def test_there_is_no_embeddings_call_to_shield() -> None:
    """An absence, pinned. pgvector is in the image; the extension is not created (ADR 0034).

    Recorded as a test rather than as a sentence in a report because "we checked and there were
    none" is the kind of claim that silently stops being true.
    """
    worker_src = Path(llm_client_module.__file__).parent.parent
    offenders = [
        str(path)
        for path in worker_src.rglob("*.py")
        if re.search(r"\.embeddings\b|embeddings\.create|def .*embed", path.read_text("utf-8"))
    ]
    assert not offenders, (
        "an embeddings call appeared in the worker. It is a provider call like any other and "
        f"has to take the same gate: {offenders}"
    )
