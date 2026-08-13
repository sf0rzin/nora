"""The gate: both rates, the adversarial fixtures, and the property behind finding 5a.

`tests/pii_corpus/harness.py` does the measuring; this file is what fails the build. The two
ceilings below are the measured rates, not aspirations -- a change that pushes either one up
fails here, which is the only thing that stops a leak fix from being paid for with a shield that
redacts the whole transcript.
"""

from __future__ import annotations

import re

import pytest

from nora_nlp.services import pii_shield
from nora_nlp.services.pii_shield import redact
from tests.pii_corpus import pools
from tests.pii_corpus.cases import (
    COMPANY_SUFFIXES,
    KNOWN_GAP,
    PAIRS_PER_QUADRANT_PER_SHAPE,
    QUADRANTS,
    REQUIRED,
    SHAPES,
    adversarial_cases,
    all_cases,
    false_positive_cases,
)
from tests.pii_corpus.harness import _contains_token, evaluate, run

# The corpus harness's own placeholder pattern, used by the 5c tests so their oracle shares no
# code with the guard they are checking.
_PLACEHOLDER_IN_TESTS = re.compile(r"\[\[[A-Z_]+_\d+\]\]")

# Both are ceilings, and both must be moved DOWN by any change that claims to improve the
# shield. Raising either one is a decision, not a detail: it belongs in a commit message that
# says which cases were traded away and why.
#
# Written as the measured fractions rather than as rounded decimals, so the gate cannot be
# passed or failed by the third digit of a number nobody re-derived.
#
#   2026-08-10, `main` at 27dc6cc -> after `_split_on_allow_list` (#431):
#       leak             16.76%  ->  9.10%   (943 -> 512 of 5627)
#       false redaction  24.83%  ->  9.60%   (1309 -> 506 of 5271)
#
#   2026-08-11, corpus grew by the false-positive pool, `pii_shield.py` UNCHANGED:
#       leak              9.10%  ->  9.10%   (512 of 5627 -> 512 of 5628)
#       false redaction   9.60%  -> 10.24%   (506 of 5271 -> 558 of 5449)
#
#   2026-08-12, corpus grew by `adv_lone_after_head` (#449), `pii_shield.py` UNCHANGED.
#   2026-08-13, corpus grew by `adv_lone_in_prose` (#450), `pii_shield.py` UNCHANGED.
#
#   Those two landed together and the constants below are the COMBINED measurement, taken
#   from a fresh run of the merged corpus rather than from either branch:
#
#       leak              9.11%  ->  9.43%   (513 of 5629 -> 533 of 5652)
#       false redaction  10.24%  -> 10.20%   (558 of 5450 -> 558 of 5470)
#
#   RECOMPUTED ON PURPOSE, and this is the part worth knowing. Both branches raised the leak
#   ceiling and both LOWERED the false-redaction one, so resolving the merge conflict by
#   keeping either branch's pair looks reasonable and is wrong in an asymmetric way: the leak
#   side fails loudly (9.43% actual against 9.34% or 9.21%), while the false-redaction side
#   PASSES SILENTLY, because both branches' values (10.22%) are looser than the true combined
#   10.20% and would leave about a case of slack on the ceiling nobody would notice.
#
# NONE of the corpus-growth rows above is a regression, and every one of them raised the leak
# ceiling, which is exactly the situation where that has to be said rather than assumed. (The
# first row is the #431 fix and lowered both rates.) The shield did not change in any of them:
#
#   2026-08-11  the corpus stopped being blind to `fp_preposition`, where 49 of 49 cases fail
#               and always did.
#   2026-08-12  it stopped being blind to a lone surname behind a phrase head and to the
#               genitive -- `Com Silva`, `Contato do Silva`, `A proposta da Costa` -- all LIVE
#               LEAKS in production with no shape here at all. Measured before the cases were
#               written: over the whole `_COMMON_PHRASE_HEADS` list x six surnames, 2,912 of
#               2,916 leak (99.86%), and 15 of 15 genitive forms.
#   2026-08-13  it stopped being blind to finding 5b's own shape. A 5b attempt measured ZERO
#               leaks closed and two false redactions caused -- a reject under the rule, and
#               then a verdict on the INSTRUMENT rather than on the rule: 424 existing cases
#               already matched that shape and not one leaked. Every one was a negative. The
#               corpus held the cost side in detail and the benefit side not at all.
#
# A ceiling that rises because the measurement got less wrong is a different thing from one
# that rises because the code got worse. The false-redaction rate FALLING across all three is
# the evidence that nothing was traded for any of them: every case added asserts something
# that must survive, so they add to that denominator and nothing to its numerator.
MAX_LEAK_RATE = 533 / 5652  # 9.43%
MAX_FALSE_REDACTION_RATE = 558 / 5470  # 10.20%

# The generated half of the corpus. Asserted so that shrinking it -- the cheapest way to make
# any rate look better -- fails instead of passing quietly.
MIN_GENERATED_NAME_CASES = 5600

# The false-positive pool. The floor matters here because the cheapest way to pass a
# false-redaction ceiling after loosening the single-token rule is to delete the cases the
# loosening broke.
MIN_FALSE_POSITIVE_CASES = 150

# And the floor that matters MORE, because the first version of this pool passed the one above
# while being entirely inert: 81 cases, 0 of which could fail under any loosening of the rule
# they claimed to price. A count is not a guarantee; this is.
#
# Measured at 34 with the pool as it stands, and at 0 with the pool as it was first written --
# both run, not reasoned. Set below the measurement on purpose: the assertion is that the pool
# bites, not that it bites exactly as hard as on the day it was written.
MIN_CASES_BROKEN_BY_LOOSENING = 20

# Floors for `test_the_corpus_prices_a_loosening_on_both_rates`. Deliberately well below the
# measured values (301 and 34 on 2026-08-12): this asserts that the corpus can SEE both sides
# of a loosening, not that it sees exactly as much as it did the day the floors were written.
MIN_LEAKS_CLOSED_BY_LOOSENING = 150
MIN_FALSE_REDACTIONS_CAUSED_BY_LOOSENING = 10


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


def test_every_company_tail_word_is_exercised() -> None:
    """A list entry no case touches is an entry nobody is measuring.

    Review deleted twelve of the twenty `_COMPANY_TAIL_WORDS` and the whole suite still passed:
    the audit above that list checked only that each entry CAN fire, never that any of them
    does. Reachability and coverage are different questions and this file had only asked one.
    """
    exercised = {pii_shield._fold(w) for w in COMPANY_SUFFIXES}
    missing = sorted(pii_shield._COMPANY_TAIL_WORDS - exercised)
    assert not missing, (
        f"{len(missing)} entries of _COMPANY_TAIL_WORDS are in no corpus case: {missing}. "
        f"Add them to cases.COMPANY_SUFFIXES or delete them from the shield."
    )


# --------------------------------------------------------------------------- #
# Where the shield's two vocabularies overlap
#
# The module keeps sets that mean "this token is ordinary vocabulary" and sets that mean "this
# token is a person". A word in both is a word whose verdict depends on which rule reaches it
# first, and that is exactly where a leak hides.
#
# This exists because of a specific mistake. A change was written that keyed a new
# "this pair is not a name" rule on `_COMMON_PHRASE_HEADS`, on the reasonable-sounding basis
# that the set means "ordinary word". It does not mean only that: `campos` and `dias` are on it
# AND on `_BR_TOP_SURNAMES`, so the new rule published `Em Campos assinou a ata.` and
# `Depois Dias confirmou o contrato.` -- both redacted before it. Nothing in the repository
# recorded that the intersection was non-empty, and adversarial review found it by reading the
# literals, which is not a method that scales.
#
# The overlap is pinned rather than forbidden, because it is deliberate and it is a trade with
# a cost on both sides:
#
#   `campos` is on `_COMMON_PHRASE_HEADS` so that "LOJA CAMPOS fechou ontem." and
#   "REGIONAL CAMPOS reportou." are not people -- the comment above that section of the shield
#   says a third of the surname list doubles as a place name.
#
#   The price is paid in the other direction and is a LEAK, live on `main`:
#   "Dias Silva aprovou o escopo." comes back untouched. `dias` heads the run, is stripped as
#   an ordinary label, one token is left and the run dies. A full name goes to the provider.
#
# So: the test does not demand the intersection be empty. It demands that it be WRITTEN DOWN.
# --------------------------------------------------------------------------- #

# Measured on `main` at d330449. Read as: "these words carry both meanings, on purpose, and
# somebody has looked at what that costs."
KNOWN_ORDINARY_NAME_OVERLAPS: dict[tuple[str, str], frozenset[str]] = {
    ("_COMMON_PHRASE_HEADS", "_BR_TOP_SURNAMES"): frozenset({"campos", "dias"}),
}

# Every set the shield reads to mean "ordinary vocabulary, not a person".
_ORDINARY_VOCABULARY_SETS = (
    "_COMMON_PHRASE_HEADS",
    "_PERSON_NAME_NEGATIVE_LIST",
    "_COMPANY_TAIL_WORDS",
    "_NAME_CONNECTIVES",
    "_GENITIVE_PREPOSITIONS",
)

# ...and the ones it reads to mean "person", or "a person follows". Honorifics are in this list
# rather than excluded from the check: `Sr` or `Dr` turning up on an ordinary-vocabulary set
# would break the prefix pattern in the same way `campos` broke the pair rule, and the overlap
# being empty today is worth pinning rather than assuming.
_NAME_VOCABULARY_SETS = (
    "_BR_TOP_NAMES",
    "_BR_TOP_SURNAMES",
    "_NAME_HONORIFICS",
    "_PERSON_ONLY_HONORIFICS",
)


@pytest.mark.parametrize("ordinary_name", _ORDINARY_VOCABULARY_SETS)
@pytest.mark.parametrize("name_set_name", _NAME_VOCABULARY_SETS)
def test_the_overlap_between_ordinary_and_name_vocabulary_is_recorded(
    ordinary_name: str, name_set_name: str
) -> None:
    """Fails in BOTH directions, and the shrinking direction is not pedantry.

    Growing means a word just acquired two meanings and some rule now decides it by accident.
    Shrinking means someone resolved one and the note beside it is now describing a tension that
    no longer exists -- which is how a comment starts lying.
    """
    ordinary = set(getattr(pii_shield, ordinary_name))
    names = set(getattr(pii_shield, name_set_name))
    actual = frozenset(ordinary & names)
    expected = KNOWN_ORDINARY_NAME_OVERLAPS.get((ordinary_name, name_set_name), frozenset())

    assert actual == expected, (
        f"{ordinary_name} & {name_set_name} is {sorted(actual)}, "
        f"recorded as {sorted(expected)}.\n\n"
        f"  added:   {sorted(actual - expected)}\n"
        f"  removed: {sorted(expected - actual)}\n\n"
        "A word in both sets means 'ordinary' to one rule and 'person' to another, and which one\n"
        "wins depends on which reaches it first. Before changing this record, work out what the\n"
        "word does on BOTH paths -- `Em Campos assinou a ata.` and `LOJA CAMPOS fechou ontem.`\n"
        "are the two sides for the existing pair, and they pull opposite ways.\n\n"
        "If you are adding a rule that reads an ordinary-vocabulary set to decide 'not a person',\n"
        "this list is what that rule will get wrong."
    )


def test_every_ordinary_vocabulary_set_is_covered_by_the_overlap_record() -> None:
    """The record above is only worth having if it is checked against every set that exists.

    A new frozenset of ordinary vocabulary added to the shield and not listed here would be
    unchecked, and the test above would pass by not looking.
    """
    checked = set(_ORDINARY_VOCABULARY_SETS) | set(_NAME_VOCABULARY_SETS)
    frozensets = {
        name
        for name in dir(pii_shield)
        if name.isupper() and isinstance(getattr(pii_shield, name), frozenset)
    }
    missing = sorted(frozensets - checked)
    assert not missing, (
        f"{len(missing)} frozenset(s) of vocabulary are not covered by the overlap check: "
        f"{missing}.\nAdd each to `_ORDINARY_VOCABULARY_SETS` or `_NAME_VOCABULARY_SETS`, or to "
        "the exclusion in this test with a reason."
    )


def test_person_only_honorifics_stay_a_subset_of_the_honorifics() -> None:
    """`_has_a_person_head` reads one and `_NAME_PREFIX_RE` the other; the containment is assumed.

    An entry in `_PERSON_ONLY_HONORIFICS` that is not in `_NAME_HONORIFICS` would vouch for a run
    the prefix pattern never matched, which is a claim with nothing behind it.
    """
    orphans = sorted(set(pii_shield._PERSON_ONLY_HONORIFICS) - set(pii_shield._NAME_HONORIFICS))
    assert not orphans, (
        f"{orphans} are in _PERSON_ONLY_HONORIFICS but not in _NAME_HONORIFICS. The first is "
        "meant to be the person-only subset of the second."
    )


def test_the_corpus_did_not_shrink() -> None:
    """Counted off the shape table, not off the id format.

    The first version counted any id with two slashes, which matches `adv/product_before/caps`
    as well -- so the adversarial set padded the total and 32 generated cases could have been
    deleted without tripping this.
    """
    generated_shapes = {name for name, _ in SHAPES}
    generated = [c for c in all_cases() if c.shape in generated_shapes]
    assert len(generated) >= MIN_GENERATED_NAME_CASES, (
        f"{len(generated)} generated cases, floor is {MIN_GENERATED_NAME_CASES}"
    )
    assert len(generated) == len(SHAPES) * len(QUADRANTS) * PAIRS_PER_QUADRANT_PER_SHAPE


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


# --------------------------------------------------------------------------- #
# The false positives
#
# Same two-test discipline as the adversarial set, applied to the pool that finding 5b's fix is
# most likely to break. Kept separate from the adversarial tests so a failure names the cost
# rather than the shape: "an ordinary word became a person" reads differently from "a gap moved".
# --------------------------------------------------------------------------- #

_FALSE_POSITIVES = false_positive_cases()


def test_the_false_positive_pool_did_not_shrink() -> None:
    assert len(_FALSE_POSITIVES) >= MIN_FALSE_POSITIVE_CASES, (
        f"{len(_FALSE_POSITIVES)} false-positive cases, floor is {MIN_FALSE_POSITIVE_CASES}. "
        "Deleting these is the cheapest way to make a single-token change look free."
    )


def test_the_false_positive_pool_is_not_inert(monkeypatch) -> None:
    """A pool that cannot fail is not a price list.

    The first version of this pool was 81 cases that all passed vacuously: every one was a lone
    Title Case token, `_is_a_name_on_its_own` is reachable only through a pattern needing two
    adjacent title words, and so not one of them could ever meet the rule they were written to
    guard. The rates said nothing was wrong because nothing in the corpus could go wrong.

    This test makes that failure mode mechanical rather than a matter of care. It applies the
    largest possible loosening of the rule -- every lone token is a name -- and demands that the
    pool NOTICE. If a future change makes the guarding cases unreachable, or deletes them, or
    replaces them with something inert, this fails while every rate still looks fine.

    The floor is deliberately below the measured count. This asserts that the pool bites, not
    that it bites exactly as hard as it did on the day it was written.
    """
    before = {c.case_id: evaluate(c).ok for c in _FALSE_POSITIVES}
    monkeypatch.setattr(pii_shield, "_is_a_name_on_its_own", lambda value: True)
    after = {c.case_id: evaluate(c).ok for c in _FALSE_POSITIVES}

    broken = sorted(cid for cid in before if before[cid] and not after[cid])
    assert len(broken) >= MIN_CASES_BROKEN_BY_LOOSENING, (
        f"only {len(broken)} of {len(_FALSE_POSITIVES)} false-positive cases break when "
        f"`_is_a_name_on_its_own` is forced to accept every lone token, and the floor is "
        f"{MIN_CASES_BROKEN_BY_LOOSENING}.\n\n"
        "That means this pool does not price a loosening of the single-token rule. Add cases "
        "that put an ordinary word where the shield can actually reach it -- inside a run that "
        "an allow-listed term or a conjunction splits -- rather than a lone noun in a sentence, "
        "which never enters that code path at all.\n\n"
        f"currently breaking: {broken}"
    )


def test_the_corpus_prices_a_loosening_on_both_rates(monkeypatch) -> None:
    """The whole corpus, not one pool, must be able to see BOTH sides of a trade.

    THIS IS THE PROPERTY FINDING 16 SAID DID NOT EXIST, and the reason 5b was never
    attempted. Measured then, over the whole corpus, forcing `_is_a_name_on_its_own` to
    accept every lone token:

        leak rate              9.0990%  ->  3.7498%   (512 -> 211)
        false-redaction rate   9.4918%  ->  9.4918%   (508 -> 508, IDENTICAL)

    A change that closed 301 leaks registered ZERO cost, while four of ten hand-written
    realistic strings broke under it. An instrument that reports a loosening as free cannot
    be used to judge one, which is why the note beside 5b says the corpus was the blocker
    rather than the fifteen lines of rule.

    Re-measured on 2026-08-12, after the false-positive pool, `fp_preposition`, the overlap
    guards and `adv_lone_after_head`:

        leak rate              9.3213%  ->  3.9872%   (526 -> 225)
        false-redaction rate  10.2367%  -> 10.8604%   (558 -> 592, +34)

    The instrument now works. This test is what keeps it working: a future corpus that
    shrinks, or that grows only in the leak direction, fails here while both rates still
    look perfectly healthy.

    ASSERTED IN BOTH DIRECTIONS ON PURPOSE. A corpus that only sees leaks closing would
    approve any loosening; one that only sees false redactions appearing would reject every
    loosening including a good one. Judging a trade needs both numbers to move.

    WHICH HALF IS ACTUALLY NEW, stated because review found the other half redundant. The 34
    cases `caused` counts are the SAME 34 that `test_the_false_positive_pool_is_not_inert`
    already counts, and against a stricter floor (20 there, 10 here) — so `caused` can never
    fail without that test failing first and harder. That capability arrived with the
    false-positive pool in #437, not here. The genuinely new assertion is `closed`: nothing
    previously checked that the corpus still contains the shapes a loosening would HELP, and
    a corpus pruned in that direction would price every loosening as pure cost.

    `caused` is kept anyway, because the pair is what makes the docstring readable as a
    statement about trades rather than two unrelated floors — and because if the pool test is
    ever narrowed, this is the second place that notices.
    """
    cases = all_cases()

    def measure() -> tuple[int, int]:
        # One pass, and it reuses `run()` rather than re-deriving the filters. The first
        # version evaluated the corpus twice per call — four times for the test — and scoped
        # false redaction to `must_survive` alone, while the published rate uses
        # `must_survive or expects_no_person`. The numerators happened to agree; a future case
        # with neither tuple would have counted toward the ceiling and been invisible here.
        report = run(cases)
        return report.leak.failed, report.false_redaction.failed

    leaks_before, fr_before = measure()
    monkeypatch.setattr(pii_shield, "_is_a_name_on_its_own", lambda value: True)
    leaks_after, fr_after = measure()

    closed = leaks_before - leaks_after
    caused = fr_after - fr_before

    assert closed >= MIN_LEAKS_CLOSED_BY_LOOSENING, (
        f"the maximum loosening closes only {closed} leaks, and the floor is "
        f"{MIN_LEAKS_CLOSED_BY_LOOSENING}. The corpus has stopped containing the shapes a "
        "loosening would help, so it can no longer show the BENEFIT side of the trade."
    )
    assert caused >= MIN_FALSE_REDACTIONS_CAUSED_BY_LOOSENING, (
        f"the maximum loosening causes only {caused} false redactions, and the floor is "
        f"{MIN_FALSE_REDACTIONS_CAUSED_BY_LOOSENING}.\n\n"
        "This is the failure finding 16 recorded: an instrument that prices a loosening at "
        "zero. It does not mean the loosening is free — it means the corpus cannot see what "
        "it costs, and any 5b-shaped change judged against it would be judged on the benefit "
        "alone. Add cases that put ordinary vocabulary where a relaxed single-token rule "
        "would reach it, rather than more names."
    )


@pytest.mark.parametrize(
    "case",
    [c for c in _FALSE_POSITIVES if c.status == REQUIRED],
    ids=lambda c: c.case_id,
)
def test_an_ordinary_word_is_not_a_person(case) -> None:
    result = evaluate(case)
    assert result.ok, (
        result.describe()
        + f"\n  shape: {case.shape}. Nothing in this string is a person -- it is ordinary pt-BR "
        "business vocabulary, and a `[[PERSON_NAME_n]]` here is a summary nobody can read.\n"
        "  If this is `fp_split_flank` or `fp_connective`, it is one of the cases that price a "
        "loosening of the single-token rule, so read it as the bill for whatever just changed."
    )


@pytest.mark.parametrize(
    "case",
    [c for c in _FALSE_POSITIVES if c.status == KNOWN_GAP],
    ids=lambda c: c.case_id,
)
def test_documented_false_positive_is_still_wrong(case) -> None:
    """A gap that closes must be promoted, not absorbed -- as in the adversarial set."""
    result = evaluate(case)
    assert not result.ok, (
        f"{case.case_id} now passes -- promote it to REQUIRED and delete the note.\n"
        f"  note was: {case.note}"
    )


# --------------------------------------------------------------------------- #
# Finding 5a as an invariant, rather than as a list of strings
# --------------------------------------------------------------------------- #

# Both halves on the shield's lists, one half, the other half, and neither -- so a verdict that
# holds for one quadrant and not the others cannot pass here.
_ADJACENCY_NAMES = (
    ("Carlos", "Silva"),
    ("Carlos", "Kranz"),
    ("Wanderleia", "Silva"),
    ("Wanderleia", "Kranz"),
    ("Kleber", "Zanchetta"),
)


@pytest.mark.parametrize("product", pools.LISTED_PRODUCTS)
@pytest.mark.parametrize("given,surname", _ADJACENCY_NAMES)
def test_an_allow_listed_term_does_not_change_the_verdict_beside_it(
    product: str, given: str, surname: str
) -> None:
    """An allow-listed term covers its own surface form and nothing else.

    Finding 5a, stated as the invariant instead of as the five strings that exposed it: whatever
    the shield decides about a name, it must decide the same thing with a product name sitting in
    front of it. `SAP CARLOS SILVA` emitted both tokens while `CARLOS SILVA` redacted correctly,
    and the negative list -- whose only possible effect anywhere in the module is to SUPPRESS a
    redaction -- was what made the difference.

    Deleting the arbitration in `_split_on_allow_list`, in either direction, fails this.
    """
    alone = redact(f"{given} {surname} aprovou o escopo.").redacted_text
    beside = redact(f"{product} {given} {surname} aprovou o escopo.").redacted_text

    assert ("[[PERSON_NAME_" in beside) == ("[[PERSON_NAME_" in alone), (
        f"{product!r} changed the verdict on {given} {surname}:\n"
        f"  alone : {alone!r}\n"
        f"  beside: {beside!r}"
    )
    assert product.lower() in beside.lower(), (
        f"the allow-listed term itself was swallowed: {beside!r}"
    )


@pytest.mark.parametrize("product", pools.LISTED_PRODUCTS)
@pytest.mark.parametrize("given,surname", _ADJACENCY_NAMES)
def test_the_same_invariant_holds_in_upper_case(product: str, given: str, surname: str) -> None:
    """The all-caps path is a separate implementation, so it needs the invariant separately.

    This is the half that was total: `SAP <NAME>` leaked on 400 of 400 generated cases, because
    the whole match was discarded rather than split.
    """
    alone = redact(f"{given.upper()} {surname.upper()} APROVOU O ESCOPO.").redacted_text
    beside = redact(
        f"{product.upper()} {given.upper()} {surname.upper()} APROVOU O ESCOPO."
    ).redacted_text

    assert ("[[PERSON_NAME_" in beside) == ("[[PERSON_NAME_" in alone), (
        f"{product!r} changed the verdict on {given} {surname} in upper case:\n"
        f"  alone : {alone!r}\n"
        f"  beside: {beside!r}"
    )
    assert product.upper() in beside, f"the allow-listed term itself was swallowed: {beside!r}"


@pytest.mark.parametrize(
    "text,must_vanish,must_survive",
    [
        # The corporate suffix is subtracted from the run, never used to refuse it. Refusing
        # sent the run to `_qualify_run`, which reads the surname off the TAIL -- the slot the
        # corporate word occupies -- so the fallback could not rescue it either, and an attendee
        # block leaked in full.
        ("Wanderleia Kranz Sistemas confirmou o prazo.", ("Wanderleia", "Kranz"), ("Sistemas",)),
        ("Kleber Silva Solutions fechou o acordo", ("Kleber", "Silva"), ("Solutions",)),
        ("Nivaldo Zanchetta Digital assumiu a conta", ("Nivaldo", "Zanchetta"), ("Digital",)),
        ("Ata assinada por\nMarina Kranz\nDiretora de Tecnologia\n", ("Marina", "Kranz"), ()),
        # TWO-TOKEN runs, which is where the first version of the subtraction leaked. Shrinking
        # a pair to one token hands it to `_is_a_name_on_its_own`, which refuses anything off
        # both name lists -- so nothing at all was claimed and the person went out in the clear.
        # The listed-surname variant was the ONLY two-token case in the first version of this
        # test, and it is the one input in the class that survives either way. Its off-list
        # twins are what caught the defect.
        ("Silva Solutions fechou o acordo", ("Silva",), ()),
        ("Kranz Solutions fechou o acordo", ("Kranz",), ()),
        ("Wanderleia Solutions fechou o acordo.", ("Wanderleia",), ()),
        ("Zanchetta Tecnologia apresentou a proposta.", ("Zanchetta",), ()),
        ("Nardelli Consultoria enviou a proposta.", ("Nardelli",), ()),
        # ...and through the genitive route, which `_qualify_run`'s own comment calls the
        # everyday shape of these transcripts.
        ("O Protheus do Kranz Solutions travou.", ("Kranz",), ("Protheus",)),
        ("Contato Kranz Solutions", ("Kranz",), ()),
        # A person, a job title and a corporate word. Pattern 1 used to claim "Gerente Software"
        # as a person; Pattern 2's longer match then overlapped it, was trimmed to the lone
        # "Odair", and thrown away. 4,484 hits in a differential, and invisible against `main`
        # because `main` has no corporate-word rule and leaks the same shape — only a diff
        # against the PREVIOUS COMMIT showed it.
        ("Odair Gerente Software confirmou.", ("Odair",), ("Software",)),
        ("Nardelli Presidente Holding confirmou.", ("Nardelli",), ("Holding",)),
        ("Zanchetta Diretor Industria apresentou a proposta.", ("Zanchetta",), ("Industria",)),
        # Same shape with a PERSON-ONLY honorific. `Sr.` and `Dr.` vouch for whatever follows
        # them in `_has_a_person_head`, so `Sr. Software` was claimed as a person and took the
        # ground from `Odair` exactly as above — 1,728 of 9,600 generated strings, and the role
        # refusal could not stop it because the vouching happens after `_trusted_span` declines.
        ("Odair Sr. Software confirmou.", ("Odair",), ("Software",)),
        ("Kranz Dr. Holding aprovou.", ("Kranz",), ("Holding",)),
        # TWO corporate words in a row, so the subtraction has to ITERATE. With `while` replaced
        # by `if` the second one is never removed and it goes into the placeholder — a mutation
        # that survived the whole suite until this case existed.
        (
            "Wanderleia Kranz Digital Solutions fechou.",
            ("Wanderleia", "Kranz"),
            ("Digital", "Solutions"),
        ),
        # A three-token trading name. The person-facing half is nothing (there is no person),
        # and what this pins is that the LAST word survives — `_name_bearing` must not also
        # exclude corporate words, which is a mutation that survived until this case came back.
        ("Northwind Software Solutions renovou o contrato", (), ("Solutions",)),
        # A job title makes the run a role only when the run holds nobody.
        ("Gerente Wanderleia Prazo confirmou", ("Wanderleia",), ()),
        ("Coordenador Edson Silva\nRelatorio - apoio", ("Edson", "Silva"), ()),
    ],
    ids=lambda v: str(v)[:40],
)
def test_a_refusal_rule_never_refuses_a_person(
    text: str, must_vanish: tuple[str, ...], must_survive: tuple[str, ...]
) -> None:
    """The two tail rules are refusals, and a refusal to redact is a potential leak.

    Every case here redacted correctly on `main` and stopped redacting under the first version
    of those rules, which discarded the whole run instead of subtracting the word. Review found
    them; they are pinned so the next refusal rule has to pay for itself.
    """
    out = redact(text).redacted_text
    stripped = re.sub(r"\[\[[A-Z_]+_\d+\]\]", " ", out)
    for token in must_vanish:
        assert token not in stripped, f"{token!r} leaked: {out!r}"
    for token in must_survive:
        assert token in stripped, f"{token!r} was swallowed: {out!r}"


@pytest.mark.parametrize("product", pools.LISTED_PRODUCTS)
def test_an_allow_listed_term_is_never_itself_redacted(product: str) -> None:
    """The other half of the arbitration rule: the term keeps its own surface form.

    A fix that satisfied the invariant above by redacting the product along with the name would
    be a worse shield, not a better one, and this is what refuses it.
    """
    for text in (
        f"A migracao do {product} entra no proximo trimestre.",
        f"{product} Carlos Silva aprovou o escopo.",
        f"{product.upper()} CARLOS SILVA APROVOU O ESCOPO.",
    ):
        out = redact(text).redacted_text
        assert product.lower() in out.lower(), f"{product!r} was redacted out of {text!r}: {out!r}"


# --------------------------------------------------------------------------- #
# Finding 5c: the tenant's own trade names, guarded by measurement
#
# The first attempt at 5c (PR #451, closed unmerged) tried to decide in advance which terms
# were safe, by refusing any that collided with _BR_TOP_NAMES | _BR_TOP_SURNAMES. Review
# proved that cannot work: those sets hold 372 tokens and this module catches most names by
# SHAPE, so the gate was blind exactly where the module says the common case lives. The three
# cases below all passed that gate and leaked a full name.
#
# So nothing here trusts a term. `redact` runs twice and keeps the second result only if every
# token it freed was declared by the tenant. These tests are the contract, not the gate.
# --------------------------------------------------------------------------- #

# Every one of these was produced by a review that broke a previous version of this feature,
# and every one is redacted correctly by the baseline pass. They are kept in the order the
# reviews found them.
#
# `NORTHWIND NIVALDO ZANCHETTA: fechamos o escopo` used to sit at the top of this list and was
# REMOVED, because it turned out to be inert: Pattern 6 never receives tenant terms, so the
# label is claimed identically in both passes and the guard is not exercised at all. It passed
# for a reason that had nothing to do with the code under test -- the same defect the rest of
# this file spends paragraphs disavowing.
LEAKED_UNDER_AN_EARLIER_GUARD = [
    # From the review that closed #451: an admitted token splits the run and strands a person
    # who has no relationship to the trade name at all.
    ("Kranz Digital Solutions", "Wanderleia Kranz Digital Solutions fechou.", ("Wanderleia",)),
    ("Casa das Maquinas", "Nivaldo das Neves aprovou o plano.", ("Nivaldo", "Neves")),
    # From the review of #452, and this is the one that killed the AGGREGATE guard. The
    # baseline redacts the first mention and leaks the second; the candidate does the exact
    # opposite. Token counts are identical in both outputs, so a multiset comparison sees
    # nothing while a person moves into the clear. Only a positional guard catches it.
    (
        "Northwind",
        "Zanchetta Northwind Kranz fechou o contrato. "
        "Relatorio de Vendas Northwind Zanchetta Kranz.",
        ("Zanchetta", "Kranz"),
    ),
]


@pytest.mark.parametrize("company,text,watch", LEAKED_UNDER_AN_EARLIER_GUARD)
def test_a_tenant_term_never_frees_a_person_it_did_not_declare(
    company: str, text: str, watch: tuple[str, ...]
) -> None:
    """The regressions that killed two earlier versions, pinned.

    THE ASSERTION IS "THE OUTPUT IS THE BASELINE", not "the token is absent, and the
    difference matters for the third case. There the baseline itself leaks both tokens at the
    second mention -- a pre-existing gap of this module that 5c neither causes nor fixes -- so
    "token absent" would fail with the feature switched off entirely. What must hold is that
    the candidate pass was DISCARDED. Anything else is the feature moving a person into the
    clear, whether or not that person's name appears elsewhere in the sentence.
    """
    terms = pii_shield.admissible_tenant_terms(company, [])
    assert terms, f"{company!r} should still be admitted -- the guard, not the gate, is the control"
    baseline = redact(text).redacted_text
    out = redact(text, terms).redacted_text

    assert out == baseline, (
        f"the guard accepted a candidate pass it should have rejected.\n"
        f"  terms    : {sorted(terms)}\n  baseline : {baseline!r}\n  got      : {out!r}\n"
        f"  watching : {list(watch)}"
    )
    # ...and nothing the baseline managed to hide may be in the clear in the output.
    hidden_by_baseline = [
        t for t in watch if t not in re.sub(r"\[\[[A-Z_]+_\d+\]\]", " ", baseline)
    ]
    stripped = re.sub(r"\[\[[A-Z_]+_\d+\]\]", " ", out)
    for token in hidden_by_baseline:
        assert token not in stripped, f"{token!r} leaked: {out!r}"


def test_no_tenant_term_can_free_a_planted_name_anywhere_in_the_corpus() -> None:
    """The contract over all 5,885 cases, with an INDEPENDENT oracle.

    The previous version of this test was TAUTOLOGICAL and review said so. It recomputed the
    guard's own expression, with the guard's own helper, on the guard's own output -- so it
    reduced to `x - x == the empty set` and was empty for every possible corpus and every
    possible term set. It could catch the guard being DELETED (it did, under mutation) and
    could never catch the guard's oracle being WRONG, which is precisely what the aggregate
    version was. 5,885 green cases certified nothing.

    So the oracle here shares no code with the guard. It is `_contains_token`, the corpus
    harness's own comparison -- the same one the published leak rate is computed with, written
    long before this feature existed. Each case knows which names it planted; this asserts that
    a name the baseline HID is not sitting in the clear in the output.

    `hostile` is the corpus's own person-name pools declared as trade names: the worst input
    this feature can receive, and one no gate would ever admit.
    """
    # A NOTE THAT WAS WRONG HERE, kept as a warning because the wrong version was confident.
    # It said: "with the guard stubbed to never reject, BOTH term sets still produce ZERO
    # violations -- that is a property of the corpus, not a bug in the sweep." The observation
    # was real and the diagnosis was invented. The sweep was passing an UNFOLDED haystack to
    # `_contains_token`, which folds only the needle, so every Title Case name missed and the
    # condition collapsed to `True and False` for every case. Zero violations, always, for any
    # shield. I measured the symptom, believed the first explanation that fit, and wrote it
    # down as fact.
    #
    # With the fold fixed and the guard stubbed to never reject, this test FAILS -- so it is a
    # liveness check after all. Measured on both term sets, 2,466 and 101 cases changing.
    #
    #   `hostile`  — declare the very names the corpus plants: the worst input possible.
    #   `disjoint` — declare only trade vocabulary, sharing no token with any planted name.
    hostile = frozenset(
        pii_shield._fold(n) for n in (list(pools.OFF_LIST_SURNAME) + list(pools.OFF_LIST_GIVEN))
    )
    disjoint = frozenset(
        pii_shield._fold(n)
        for n in (list(COMPANY_SUFFIXES) + list(pools.UNLISTED_PRODUCTS))
        if len(pii_shield._fold(n)) >= pii_shield._TENANT_TERM_MIN_LENGTH
    )
    assert not (disjoint & hostile), (
        "the 'disjoint' term set now shares a token with the corpus's person pools, so it can "
        "no longer prove anything a broken guard would fail. Re-derive it."
    )

    violations = []
    changed = 0
    for case in all_cases():
        base = redact(case.text).redacted_text
        for terms in (hostile, disjoint):
            out = redact(case.text, terms).redacted_text
            if out != base:
                changed += 1
            _collect_freed_names(case, base, out, terms, violations)

    assert not violations, (
        f"{len(violations)} case(s) moved a planted name into the clear. First few:\n"
        + "\n".join(
            f"  {t!r}\n    token={k!r}\n    base={b!r}\n    out={o!r}"
            for t, k, b, o in violations[:5]
        )
    )
    # It has to be able to fail, or a green run means nothing.
    assert changed > 500, (
        f"only {changed} cases changed under the term sets; this check has stopped exercising "
        "the guard and would pass with the feature disabled"
    )


def _collect_freed_names(case, base: str, out: str, terms, violations: list) -> None:
    """A planted name the BASELINE hid must not be in the clear in `out`.

    Names the baseline already leaks are excluded: that is a gap of the shield -- 9.43% of the
    corpus -- and not something this feature did. Comparison is `_contains_token`, the corpus
    harness's own, so this oracle shares no code with the guard it is checking.
    """
    # `_fold` ON THE HAYSTACK, and the parameter name in `harness.py` is the contract:
    # `_contains_token(haystack_folded, token)` folds the NEEDLE and does a case-sensitive
    # search. Passing raw text made every Title Case name miss -- `kranz` never matches
    # "Kranz" -- so both calls returned False, the condition collapsed to `True and False`,
    # and this sweep was unconditionally green for every corpus, term set and shield.
    # Measured after the fix, not reasoned. `harness.evaluate` folds at the same point.
    base_clear = pii_shield._fold(_PLACEHOLDER_IN_TESTS.sub(" ", base))
    out_clear = pii_shield._fold(_PLACEHOLDER_IN_TESTS.sub(" ", out))
    for token in case.must_vanish:
        if pii_shield._fold(token) in terms:
            continue  # declared by this tenant: freeing it is the feature, not a violation
        if not _contains_token(base_clear, token) and _contains_token(out_clear, token):
            violations.append((case.text, token, base, out))


def test_empty_tenant_terms_change_nothing_at_all() -> None:
    """Empty must not fail open -- as IDENTITY, over the corpus, not as "still redacts"."""
    for empty in (
        pii_shield.admissible_tenant_terms("", []),
        pii_shield.admissible_tenant_terms(None, None),
        frozenset(),
    ):
        assert empty == frozenset()
        for case in all_cases():
            assert redact(case.text, empty).redacted_text == redact(case.text).redacted_text


@pytest.mark.parametrize("company", ["Northwind", "Contoso", "Zendesk"])
@pytest.mark.parametrize("given,surname", [("Andre", "Teixeira"), ("Marina", "Alves")])
def test_an_admitted_term_keeps_its_name_without_freeing_the_person(
    company: str, given: str, surname: str
) -> None:
    """5c itself. The company survives; the person does not.

    None of these three is in `_PERSON_NAME_NEGATIVE_LIST` -- checked by
    `test_the_5c_sample_is_not_already_handled` below, because the previous attempt's sample
    included Protheus and Datasul, which are, so four of its ten cases were inert.
    """
    terms = pii_shield.admissible_tenant_terms(company, [])
    text = f"{company} {given} {surname} confirmou a renovacao."
    out = redact(text, terms).redacted_text

    assert company in out, f"{company!r} was eaten with the person: {out!r}"
    stripped = re.sub(r"\[\[[A-Z_]+_\d+\]\]", " ", out)
    assert given not in stripped, f"{given!r} leaked: {out!r}"
    assert surname not in stripped, f"{surname!r} leaked: {out!r}"


@pytest.mark.parametrize("company", ["Northwind", "Contoso", "Zendesk"])
def test_the_5c_sample_is_not_already_handled(company: str) -> None:
    """Stops the headline test above from going inert the way the previous one did.

    A term already on the static negative list produces identical output with and without the
    feature, so a sample drawn from those would assert nothing about 5c at all.
    """
    assert pii_shield._fold(company) not in pii_shield._PERSON_NAME_NEGATIVE_LIST, (
        f"{company!r} is now on the static negative list, so this case no longer exercises "
        "the tenant-term path. Pick a term that is not already handled."
    )


def test_the_residual_risk_is_what_the_contract_says_it_is() -> None:
    """The honest limit, pinned so it cannot drift into a stronger claim.

    A tenant that declares a term which is ALSO a person's surname keeps that surname in the
    clear for its own transcripts. That is not a hole in the guard -- the token was declared,
    and the feature is definitionally "let the tenant keep its own name". It is the
    irreducible cost, and it is recorded here rather than in a comment nobody re-runs.
    """
    terms = pii_shield.admissible_tenant_terms("Nardelli Consultoria", [])
    out = redact("Nardelli Consultoria enviou a proposta.", terms).redacted_text
    assert "Nardelli" in out, (
        "the residual changed shape. Re-derive it before relaxing this test: the guard's "
        "promise is 'no UNDECLARED token is freed', never 'no surname is ever freed'."
    )
    # ...and the same surname is still redacted for a tenant that did NOT declare it.
    assert "Nardelli" not in redact("Nardelli Consultoria enviou a proposta.").redacted_text


def test_declared_means_fold_equivalent_not_equal() -> None:
    """The residual is wider than "the exact string the tenant typed", and that is worth pinning.

    `_fold` strips accents, so a declaration covers every accent variant of itself. Every
    membership test in the shield is on folded tokens, so this is the only self-consistent
    rule -- a narrower one here would make a declared term fail to match the text it was
    declared for. But nobody reading a settings page would infer it, so it is asserted.

    The example USED to be "Ines Consultoria", which now fails admission and rightly so:
    `ines` is person vocabulary, and the gate refuses those since a competitor named after a
    surname was measured exposing third parties. The property being pinned here is about
    folding, so it needs a term that is not a name -- otherwise this test would be quietly
    asserting two things and breaking for the wrong reason.
    """
    assert pii_shield._fold("Ant\u00f4nio") == pii_shield._fold("Antonio")
    assert pii_shield._fold("Inova\u00e7\u00e3o") == pii_shield._fold("Inovacao")

    terms = pii_shield.admissible_tenant_terms("Inovacao Digital", [])
    assert pii_shield._fold("Inova\u00e7\u00e3o") in terms, (
        "the accented form is no longer covered by the unaccented declaration. That is a "
        "narrower residual, which is fine -- but the guard compares folded tokens, so check "
        "that a declared term still matches the text it was declared for before relaxing this."
    )

    # The accented spelling survives in the transcript even though the tenant typed it plain...
    out = redact("Inova\u00e7\u00e3o Digital e Beatriz Moreira assinaram.", terms).redacted_text
    assert "Inova\u00e7\u00e3o" in out, f"the accented form was not covered: {out!r}"
    # ...and a person who is not fold-equivalent to any declared term is still redacted.
    stripped = re.sub(r"\[\[[A-Z_]+_\d+\]\]", " ", out)
    assert "Moreira" not in stripped, f"an undeclared surname leaked: {out!r}"
    assert "Beatriz" not in stripped, f"an undeclared given name leaked: {out!r}"


@pytest.mark.parametrize(
    "competitor,text,third_party_surname",
    [
        ("Silva Tecnologia", "Dr. Carlos Silva aprovou o contrato.", "Silva"),
        ("Santos Group", "Dr. Marina Santos assinou a proposta.", "Santos"),
        ("Oliveira Labs", "Contato: Bruno Oliveira, diretor.", "Oliveira"),
    ],
)
def test_a_competitor_named_after_a_surname_does_not_expose_third_parties(
    competitor: str, text: str, third_party_surname: str
) -> None:
    """The half of the residual that was NOT tested, and it is the half that matters.

    `test_the_residual_risk_is_what_the_contract_says_it_is` covers the benign case: the token
    the tenant declared IS the tenant. This covers the malign one -- a `competitors` entry
    named after a surname, and a THIRD PARTY on the call who happens to share it. Silva is the
    commonest surname in Brazil.

    Measured before this test existed, with the vocabulary check removed from the gate:
    "Dr. Carlos Silva aprovou o contrato." came out as "[[PERSON_NAME_1]] Silva aprovou o
    contrato." in 4 of 4 shapes tried. The guard permitted it correctly by its own contract --
    `silva` was declared -- which is exactly why the guard cannot be the only control.
    """
    terms = pii_shield.admissible_tenant_terms(None, [competitor])
    assert terms == frozenset(), (
        f"{competitor!r} was admitted. Its surname token is person vocabulary, and admitting it "
        f"exposes every {third_party_surname} in this tenant's transcripts -- people who are "
        "not the tenant and never agreed to anything."
    )
    assert redact(text, terms).redacted_text == redact(text).redacted_text
    visible = _PLACEHOLDER_IN_TESTS.sub(" ", redact(text, terms).redacted_text)
    assert third_party_surname not in visible, f"third-party surname exposed: {visible!r}"
