"""Wall-clock budget shared by every LLM call of one request.

The caller is the Spring API, whose WebClient gives up on the worker after
``NlpWorkerProperties.timeoutMillis`` = 120_000 ms (see
``services/api/src/main/java/br/com/nora/api/infrastructure/nlp/NlpWorkerProperties.java``).
Nothing in the worker knew that number existed. ``LlmClient`` caps ONE provider call at 60s and
lets the SDK retry it twice; each analyzer then re-sends the whole prompt in JSON mode when the
structured call raises, for three more attempts. Six attempts of 60s is a 360s ceiling for one
call -- per WINDOW on ``/split`` -- against a caller that left at 120s. The worker went on
spending paid tokens for four more minutes producing an answer nobody was there to receive.

Lowering the per-call timeout only moves that ceiling; it does not create a relationship between
the two numbers. What does is measuring the wall clock from the start of the request and
deciding, BEFORE each further attempt, whether it still fits, so the worker gives up on its own
a margin ahead of the caller's deadline instead of well after it.

The budget belongs to the request, not to the call: one instance is created in the router and
threaded through the analyzer into the client, so the JSON-mode fallback and -- on ``/split`` --
every window after the first are spending the same seconds instead of each starting from zero.
"""

from __future__ import annotations

import time
from collections.abc import Callable

from .settings import Settings

# Under this, a further attempt is not worth starting: a call on this path takes tens of seconds
# at the p99 (see ``LlmClient._LLM_TIMEOUT_SECONDS``), so a shorter slice buys a request that is
# paid for and then cut off. Giving up is the cheaper of the two failures.
MIN_ATTEMPT_SECONDS = 20.0


class LlmBudgetExceededError(RuntimeError):
    """The request's wall-clock budget ran out before the work finished.

    A ``RuntimeError`` and deliberately not a ``ValueError``: the router's ``ValueError`` branch
    means "the LLM_* environment is wrong", and nothing here is wrong with the configuration or
    with the model's answer -- the same misclassification ``json.JSONDecodeError`` fell into
    before it got a branch of its own.

    The message carries a step name and durations, never a prompt or a response. ADR 0012.
    """

    def __init__(self, step: str, *, elapsed: float, total: float) -> None:
        super().__init__(
            f"the request's {total:.0f}s LLM budget was spent before {step} "
            f"({elapsed:.1f}s elapsed)"
        )
        self.step = step
        self.elapsed = elapsed
        self.total = total


class TimeBudget:
    """Seconds this request may still spend on the provider, counted from its own creation."""

    def __init__(
        self, total_seconds: float, *, clock: Callable[[], float] = time.monotonic
    ) -> None:
        # ``time.monotonic`` and not ``time.time``: a clock step (NTP) must not hand a request
        # more budget than it was given, nor cut a healthy one short. Injectable so that a test
        # can spend the budget without spending the wall clock.
        self._clock = clock
        self._total = total_seconds
        self._started = clock()

    @classmethod
    def from_settings(
        cls, settings: Settings, *, clock: Callable[[], float] = time.monotonic
    ) -> TimeBudget:
        return cls(settings.llm_request_budget_seconds, clock=clock)

    @property
    def total_seconds(self) -> float:
        return self._total

    def elapsed(self) -> float:
        return self._clock() - self._started

    def remaining(self) -> float:
        return self._total - self.elapsed()

    def remaining_for(self, step: str) -> float:
        """Seconds left, or ``LlmBudgetExceededError`` when they cannot pay for a useful attempt.

        Called before starting ``step`` and never after it: the point is to not begin work whose
        result arrives once the caller has gone.
        """
        remaining = self.remaining()
        if remaining < MIN_ATTEMPT_SECONDS:
            raise LlmBudgetExceededError(step, elapsed=self.elapsed(), total=self._total)
        return remaining
