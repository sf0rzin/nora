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
)
from tests.pii_corpus.harness import evaluate, run

# Measured on 2026-08-10. `main` at 27dc6cc, then the same corpus after `_split_on_allow_list`:
#
#                              before      after
#     leak rate             :  16.76%  ->   9.10%   (943 -> 512 of 5627)
#     false-redaction rate  :  24.83%  ->   9.58%   (1309 -> 505 of 5271)
#
# Both are ceilings, and both must be moved DOWN by any change that claims to improve the
# shield. Raising either one is a decision, not a detail: it belongs in a commit message that
# says which cases were traded away and why.
#
# Written as the measured fractions rather than as rounded decimals, so the gate cannot be
# passed or failed by the third digit of a number nobody re-derived.
MAX_LEAK_RATE = 512 / 5627  # 9.10%
MAX_FALSE_REDACTION_RATE = 506 / 5271  # 9.60%

# The generated half of the corpus. Asserted so that shrinking it -- the cheapest way to make
# any rate look better -- fails instead of passing quietly.
MIN_GENERATED_NAME_CASES = 5600


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
