"""The gate: both rates, the adversarial fixtures, and the property behind finding 5a.

`tests/pii_corpus/harness.py` does the measuring; this file is what fails the build. The two
ceilings below are the measured rates, not aspirations -- a change that pushes either one up
fails here, which is the only thing that stops a leak fix from being paid for with a shield that
redacts the whole transcript.
"""

from __future__ import annotations

import pytest

from nora_nlp.services import pii_shield
from tests.pii_corpus import pools
from tests.pii_corpus.cases import KNOWN_GAP, REQUIRED, adversarial_cases, all_cases
from tests.pii_corpus.harness import evaluate, run

# Measured on 2026-08-10 against main at 27dc6cc, before any change to the shield:
#
#     leak rate             :  21.30%  (943 / 4427 cases)
#     false-redaction rate  :   5.26%  (109 / 2071 cases)
#
# Both are ceilings, and both must be moved DOWN by any change that claims to improve the
# shield. Raising either one is a decision, not a detail: it belongs in a commit message that
# says which cases were traded away and why.
#
# Written as the measured fractions rather than as rounded decimals, so the gate cannot be
# passed or failed by the third digit of a number nobody re-derived.
MAX_LEAK_RATE = 943 / 4427  # 21.30%
MAX_FALSE_REDACTION_RATE = 109 / 2071  # 5.26%

# The generated half of the corpus. Asserted so that shrinking it -- the cheapest way to make
# any rate look better -- fails instead of passing quietly.
MIN_GENERATED_NAME_CASES = 4400


@pytest.fixture(scope="module")
def report():
    return run(all_cases())


# --------------------------------------------------------------------------- #
# The corpus itself has to be honest before the rates mean anything
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("name", pools.OFF_LIST_GIVEN)
def test_off_list_given_names_are_really_off_the_list(name: str) -> None:
    assert pii_shield._fold(name) not in pii_shield._BR_TOP_NAMES, (
        f"{name!r} is now on _BR_TOP_NAMES. The corpus quadrants no longer mean what they say: "
        f"move it to ON_LIST_GIVEN so the off-list quadrant keeps measuring off-list names."
    )


@pytest.mark.parametrize("name", pools.OFF_LIST_SURNAME)
def test_off_list_surnames_are_really_off_the_list(name: str) -> None:
    assert pii_shield._fold(name) not in pii_shield._BR_TOP_SURNAMES, (
        f"{name!r} is now on _BR_TOP_SURNAMES; move it to ON_LIST_SURNAME."
    )


@pytest.mark.parametrize("name", pools.ON_LIST_GIVEN)
def test_on_list_given_names_are_really_on_the_list(name: str) -> None:
    assert pii_shield._fold(name) in pii_shield._BR_TOP_NAMES


@pytest.mark.parametrize("name", pools.ON_LIST_SURNAME)
def test_on_list_surnames_are_really_on_the_list(name: str) -> None:
    assert pii_shield._fold(name) in pii_shield._BR_TOP_SURNAMES


@pytest.mark.parametrize("product", pools.LISTED_PRODUCTS)
def test_listed_products_are_really_on_the_negative_list(product: str) -> None:
    assert pii_shield._fold(product) in pii_shield._PERSON_NAME_NEGATIVE_LIST


@pytest.mark.parametrize("product", pools.UNLISTED_PRODUCTS)
def test_unlisted_products_are_really_off_the_negative_list(product: str) -> None:
    assert pii_shield._fold(product) not in pii_shield._PERSON_NAME_NEGATIVE_LIST


def test_the_corpus_did_not_shrink() -> None:
    generated = [c for c in all_cases() if "/" in c.case_id and c.case_id.count("/") == 2]
    assert len(generated) >= MIN_GENERATED_NAME_CASES


# --------------------------------------------------------------------------- #
# The two rates
# --------------------------------------------------------------------------- #


def test_leak_rate_does_not_regress(report) -> None:
    rate = report.leak
    assert rate.value <= MAX_LEAK_RATE, (
        f"leak rate {rate.value:.4%} exceeds the ceiling {MAX_LEAK_RATE:.4%}\n\n" + report.render()
    )


def test_false_redaction_rate_does_not_regress(report) -> None:
    rate = report.false_redaction
    assert rate.value <= MAX_FALSE_REDACTION_RATE, (
        f"false-redaction rate {rate.value:.4%} exceeds the ceiling "
        f"{MAX_FALSE_REDACTION_RATE:.4%}\n\n" + report.render()
    )


def test_the_report_never_states_one_rate_without_the_other(report) -> None:
    """The rule this corpus exists to enforce, asserted on the artefact people will read."""
    rendered = report.render()
    assert "leak rate" in rendered
    assert "false-redaction rate" in rendered


# --------------------------------------------------------------------------- #
# The adversarial set
# --------------------------------------------------------------------------- #

_ADVERSARIAL = adversarial_cases()


@pytest.mark.parametrize(
    "case",
    [c for c in _ADVERSARIAL if c.status == REQUIRED],
    ids=lambda c: c.case_id,
)
def test_adversarial_case_holds(case) -> None:
    result = evaluate(case)
    assert result.ok, result.describe() + (f"\n  note: {case.note}" if case.note else "")


@pytest.mark.parametrize(
    "case",
    [c for c in _ADVERSARIAL if c.status == KNOWN_GAP],
    ids=lambda c: c.case_id,
)
def test_documented_gap_is_still_a_gap(case) -> None:
    """Strict on purpose: closing a gap must promote it, not absorb it.

    A `KNOWN_GAP` that starts passing is good news and still fails here, because the alternative
    is a corpus that slowly fills with cases nobody has looked at since they were written.
    """
    result = evaluate(case)
    assert not result.ok, (
        f"{case.case_id} now passes -- promote it to REQUIRED and delete the note.\n"
        f"  note was: {case.note}"
    )
