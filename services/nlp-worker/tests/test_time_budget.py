"""The worker's aggregated wall-clock budget, and the arithmetic it was written to bound.

The caller gives up on the worker after `NlpWorkerProperties.timeoutMillis` = 120_000 ms
(`services/api/src/main/java/br/com/nora/api/infrastructure/nlp/NlpWorkerProperties.java`). The
worker used to be able to spend 360s answering it: `LlmClient` caps one provider call at 60s and
lets the SDK retry it twice, and each analyzer re-sends the whole prompt in JSON mode when the
structured call raises -- three attempts, then three more. On `/split` that ceiling was per
WINDOW, so a file large enough for five windows had no ceiling worth naming. Nobody received any
of it; the tokens were paid for all the same.

These tests pin the two halves of the fix that a smaller constant would not have given:

  * the budget is AGGREGATED -- one request's calls draw on the same seconds, so window 4
    answers for what windows 1-3 spent -- and it is consulted BEFORE each further attempt, not
    after;
  * a call is never issued with a timeout the remaining budget cannot pay for, counting the
    SDK's own retries, which are the multiplier the original 60s constant ignored.

The clock is injected rather than waited on: spending a 90s budget must not cost 90 seconds.
"""

from __future__ import annotations

import json
import logging
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from nora_nlp.clients.llm import LlmClient
from nora_nlp.main import app
from nora_nlp.models import AnalyzeRequest, LiveAnalyzeRequest, SplitRequest
from nora_nlp.services import live_analyzer, llm_analyzer, split_analyzer
from nora_nlp.settings import Settings, get_settings
from nora_nlp.time_budget import MIN_ATTEMPT_SECONDS, LlmBudgetExceededError, TimeBudget

client = TestClient(app)

# The caller's deadline, in seconds, transcribed from NlpWorkerProperties.timeoutMillis. It is
# repeated here so that raising the worker's default past it fails a test rather than a customer.
CALLER_DEADLINE_SECONDS = 120.0

_TRANSCRIPT = (
    "[Speaker_1] Bom dia, vamos revisar o escopo do rollout.\n"
    "[Speaker_2] O lote reduzido fica decidido para a primeira carga.\n"
    "[Speaker_1] Fechamos assim.\n"
)

_TENANT_CONTEXT = {
    "companyName": "Northwind",
    "industry": "ERP",
    "valueProposition": "ERP para industria",
    "products": [],
    "competitors": [],
    "objectionHandling": [],
    "glossary": [],
}

_ANALYZE_BODY = {
    "meetingId": "test-meeting-time-budget",
    "tenantId": "00000000-0000-4000-8000-000000000001",
    "language": "pt-BR",
    "transcript": _TRANSCRIPT,
    "tenantContext": _TENANT_CONTEXT,
}

_LIVE_BODY = {"transcriptChunk": _TRANSCRIPT, "language": "pt-BR"}

_SPLIT_BODY = {"transcript": _TRANSCRIPT, "language": "pt-BR"}

_ROUTES = (("/analyze", _ANALYZE_BODY), ("/analyze-live", _LIVE_BODY), ("/split", _SPLIT_BODY))


class _StepClock:
    """A monotonic clock that advances a fixed amount every time it is READ.

    Reads are not free here, and that is the point: it lets a test spend a 90s budget in no time
    at all, and it keeps the number of budget checks visible, so a check added inside a loop
    cannot quietly cost nothing.
    """

    def __init__(self, step: float) -> None:
        self._step = step
        self._now = 0.0

    def __call__(self) -> float:
        now = self._now
        self._now += self._step
        return now


def _make_settings(budget_seconds: float = 90.0) -> Settings:
    return Settings(
        llm_provider="openai",
        llm_base_url="https://api.openai.com/v1",
        llm_api_key="sk-test-fake-1234567890",
        llm_model="gpt-4o-mini",
        llm_temperature=0.2,
        llm_request_budget_seconds=budget_seconds,
        use_llm_stub=False,
    )


# ---------- The default, against the number it exists to stay under ----------


def test_the_default_budget_leaves_the_caller_room_to_still_be_listening():
    """A default above the caller's deadline is the defect wearing the fix's clothes.

    The margin is not decoration: the PII shield, the TF-IDF baseline, JSON serialization and
    the HTTP round trip all happen inside the caller's 120s and outside the provider call, so
    the budget has to end before the deadline does, with room to answer.
    """
    default = Settings().llm_request_budget_seconds
    assert default < CALLER_DEADLINE_SECONDS, (
        f"the worker may spend {default}s on the provider while the caller leaves at "
        f"{CALLER_DEADLINE_SECONDS}s -- it would again be burning paid tokens for an answer "
        "nobody is there to receive"
    )
    assert CALLER_DEADLINE_SECONDS - default >= 20.0, "no room left to deliver the answer"


# ---------- TimeBudget itself ----------


def test_the_budget_refuses_an_attempt_it_cannot_pay_for():
    budget = TimeBudget(90.0, clock=_StepClock(0.0))
    assert budget.remaining_for("a first call") == pytest.approx(90.0)

    spent = TimeBudget(MIN_ATTEMPT_SECONDS - 1.0, clock=_StepClock(0.0))
    with pytest.raises(LlmBudgetExceededError) as caught:
        spent.remaining_for("a call with nothing left")
    # The message is what reaches the log, so it carries the step and the seconds and nothing
    # from the request. ADR 0012.
    assert "a call with nothing left" in str(caught.value)


def test_the_budget_shrinks_with_the_wall_clock_and_not_with_the_call_count():
    """Elapsed time is the only thing that spends it -- one slow call costs what three fast
    ones do not."""
    budget = TimeBudget(90.0, clock=_StepClock(30.0))
    assert budget.remaining_for("first") == pytest.approx(60.0)
    assert budget.remaining_for("second") == pytest.approx(30.0)
    with pytest.raises(LlmBudgetExceededError):
        budget.remaining_for("third")


# ---------- The per-call sizing, which is where the 3x came from ----------


@pytest.mark.parametrize("remaining", [300.0, 90.0, 45.0, 30.0, 21.0])
def test_one_call_can_never_outlast_what_the_budget_has_left(remaining):
    """`max_retries` multiplies the timeout; the original 60s constant only ever bounded one
    attempt out of three, which is the whole of the 3x overrun.

    The SDK client here is the real one -- constructing it opens no connection, and the sizing
    is read back off the object that would make the call rather than off a mock that would
    agree with whatever it was told.
    """
    llm = LlmClient(_make_settings(), budget=TimeBudget(remaining, clock=_StepClock(0.0)))
    sized = llm._client_for("a call")

    timeout = sized.timeout
    attempts = sized.max_retries + 1
    assert attempts * timeout <= remaining + 1e-9, (
        f"{attempts} attempts of {timeout}s exceed the {remaining}s the request has left"
    )
    assert timeout <= LlmClient._LLM_TIMEOUT_SECONDS
    assert timeout > 0


def test_a_shrinking_budget_buys_fewer_retries_before_it_buys_shorter_attempts():
    """The trade has a direction. Three attempts all cut off short is the worst of both: it
    pays three times for nothing. One attempt long enough to answer is worth more.
    """
    settings = _make_settings()
    roomy = LlmClient(settings, budget=TimeBudget(90.0, clock=_StepClock(0.0)))._client_for("x")
    tight = LlmClient(settings, budget=TimeBudget(30.0, clock=_StepClock(0.0)))._client_for("x")

    assert roomy.max_retries == LlmClient._MAX_RETRIES
    assert tight.max_retries == 0
    assert tight.timeout >= MIN_ATTEMPT_SECONDS


def test_without_a_budget_the_client_behaves_as_it_did():
    """A script or a notebook has no caller waiting on it; only the three analyzers do."""
    llm = LlmClient(_make_settings())
    assert llm._client_for("a call") is llm._client
    assert llm._client.timeout == LlmClient._LLM_TIMEOUT_SECONDS
    assert llm._client.max_retries == LlmClient._MAX_RETRIES


# ---------- The analyzers ----------


def test_the_json_mode_fallback_is_not_attempted_once_the_budget_is_gone():
    """The fallback re-sends the ENTIRE prompt. It is the single most expensive thing the
    analyzer can do, and an exhausted budget is exactly the case where its answer arrives after
    the caller has gone. `LlmBudgetExceededError` is a `RuntimeError`, so the generic
    `except Exception` that drives the fallback would otherwise swallow it and pay again.
    """
    instance = MagicMock()
    instance.chat_structured.side_effect = LlmBudgetExceededError(
        "the structured call", elapsed=95.0, total=90.0
    )
    req = AnalyzeRequest(
        meetingId="test-meeting-time-budget",
        tenantId="00000000-0000-4000-8000-000000000001",
        language="pt-BR",
        transcript=_TRANSCRIPT,
        tenantContext=_TENANT_CONTEXT,
    )

    with (
        patch("nora_nlp.services.llm_analyzer.LlmClient", return_value=instance),
        pytest.raises(LlmBudgetExceededError),
    ):
        llm_analyzer.analyze(req, _make_settings())

    instance.chat_json.assert_not_called()


def test_the_live_path_does_not_fall_back_either_once_the_budget_is_gone():
    instance = MagicMock()
    instance.chat_structured.side_effect = LlmBudgetExceededError(
        "the structured call", elapsed=95.0, total=90.0
    )
    req = LiveAnalyzeRequest(transcriptChunk=_TRANSCRIPT, language="pt-BR")

    with (
        patch("nora_nlp.services.live_analyzer.LlmClient", return_value=instance),
        pytest.raises(LlmBudgetExceededError),
    ):
        live_analyzer.analyze(req, _make_settings())

    instance.chat_json.assert_not_called()


def test_split_spends_one_budget_across_its_windows_and_not_one_per_window(monkeypatch):
    """The half of the finding that only `/split` had.

    Each window used to be free to burn the whole retry ladder on its own, so the ceiling was
    360s multiplied by the number of windows -- and a 1MB file is up to five of them. With the
    budget aggregated, the windows that run first are what stops the later ones: this transcript
    needs half a dozen windows and the clock only pays for two.

    It raises instead of returning the two windows it managed. A partial boundary list is a
    wrong answer that looks like a right one, and the client slices a real file on it.
    """
    transcript = "\n".join(
        ["=== Reuniao A ==="]
        + [f"A fala numero {i} da primeira reuniao" for i in range(1, 10)]
        + ["=== Reuniao B ==="]
        + [f"B fala numero {i} da segunda reuniao" for i in range(1, 10)]
    )
    redacted_lines, _ = split_analyzer.redact_lines(transcript)
    monkeypatch.setattr(split_analyzer, "_WINDOW_CHAR_BUDGET", 120)  # ~3 lines per window

    instance = MagicMock()
    instance.chat_structured.return_value = (json.dumps({"segments": []}), 10, 5)

    # 25s per budget read against a 90s budget: two windows fit, the third does not.
    budget = TimeBudget(90.0, clock=_StepClock(25.0))
    req = SplitRequest(transcript=transcript, language="pt-BR")

    with (
        patch("nora_nlp.services.split_analyzer.LlmClient", return_value=instance),
        pytest.raises(LlmBudgetExceededError),
    ):
        split_analyzer.analyze(req, redacted_lines, _make_settings(), budget=budget)

    assert instance.chat_structured.call_count == 2, (
        "the budget is being read per window instead of across the request -- with windows still "
        f"to go it allowed {instance.chat_structured.call_count}"
    )


def test_split_finishes_normally_when_the_budget_holds(monkeypatch):
    """The guard must not be the thing that breaks the endpoint it protects."""
    transcript = "\n".join(f"linha {i} da reuniao" for i in range(1, 21))
    redacted_lines, _ = split_analyzer.redact_lines(transcript)
    monkeypatch.setattr(split_analyzer, "_WINDOW_CHAR_BUDGET", 220)

    instance = MagicMock()
    instance.chat_structured.return_value = (json.dumps({"segments": []}), 10, 5)
    req = SplitRequest(transcript=transcript, language="pt-BR")

    with patch("nora_nlp.services.split_analyzer.LlmClient", return_value=instance):
        resp = split_analyzer.analyze(req, redacted_lines, _make_settings())

    assert instance.chat_structured.call_count >= 2, "expected multiple windows"
    assert resp.total_lines == len(redacted_lines)
    assert resp.segments[-1].end_line == len(redacted_lines)


# ---------- The routes ----------


@pytest.fixture
def _exhausted_budget():
    """A budget of zero: every route must give up before it calls the provider at all."""
    app.dependency_overrides[get_settings] = lambda: _make_settings(budget_seconds=0.0)
    yield
    app.dependency_overrides.pop(get_settings, None)


@pytest.mark.parametrize("route,body", _ROUTES, ids=lambda v: str(v))
def test_an_exhausted_budget_is_a_gateway_timeout_and_not_a_server_error(
    route, body, _exhausted_budget, caplog
) -> None:
    """The classification, for the same reason the two branches beside it have tests.

    `LlmBudgetExceededError` is a `RuntimeError`, so with no branch of its own it lands in the
    generic handler and is published as 500 `LLM_PROVIDER_ERROR` -- "Error processing the
    transcript. Please try again." Nothing failed and retrying is not the advice: the worker
    measured the caller's deadline and declined to spend against it. 504 says that; 500 sends
    whoever reads it looking for a provider outage that is not happening.

    `OpenAI` is patched so a regression fails on the assertion rather than on the network. The
    provider is never reached on this path -- that is what the budget is for.
    """
    with caplog.at_level(logging.DEBUG), patch("nora_nlp.clients.llm.OpenAI"):
        resp = client.post(route, json=body)

    assert resp.status_code == 504, resp.text
    detail = resp.json()["detail"]
    assert detail["code"] == "LLM_BUDGET_EXCEEDED"
    # And what it writes down is a step and a duration, never the meeting. ADR 0012.
    for token in ("lote reduzido", "rollout", "Northwind"):
        assert token not in caplog.text, (
            f"{token!r} from the request reached the log:\n{caplog.text}"
        )
