"""The cases themselves: 4,400 generated, plus a hand-written adversarial set.

A case declares what must *vanish* (a person's name) and what must *survive* (a product, a
company, a role). Most cases declare both, which is deliberate: a fix that redacts the whole
sentence satisfies the first and fails the second, and that is the failure mode this corpus
exists to catch.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from . import pools

REQUIRED = "REQUIRED"
KNOWN_GAP = "KNOWN_GAP"


@dataclass(frozen=True)
class Case:
    """One string, with what the shield owes on it.

    `must_vanish` and `must_survive` are single word tokens, compared accent-folded and
    case-insensitively against the output with the placeholders removed.

    `status` is `REQUIRED` when the shield is expected to satisfy the case, or `KNOWN_GAP` when
    it is not: a gap carries a `note` saying why, is counted in the published rates like any
    other failure, and is asserted to *still fail* -- so closing one forces it to be promoted
    rather than silently absorbed.
    """

    case_id: str
    shape: str
    text: str
    must_vanish: tuple[str, ...] = ()
    must_survive: tuple[str, ...] = ()
    status: str = REQUIRED
    note: str = ""
    tags: tuple[str, ...] = field(default_factory=tuple)

    @property
    def expects_no_person(self) -> bool:
        """A case with nobody in it must not produce a PERSON_NAME placeholder at all."""
        return not self.must_vanish


# --------------------------------------------------------------------------- #
# Generated corpus
# --------------------------------------------------------------------------- #

QUADRANTS: tuple[tuple[str, tuple[str, ...], tuple[str, ...]], ...] = (
    ("on_on", pools.ON_LIST_GIVEN, pools.ON_LIST_SURNAME),
    ("on_off", pools.ON_LIST_GIVEN, pools.OFF_LIST_SURNAME),
    ("off_on", pools.OFF_LIST_GIVEN, pools.ON_LIST_SURNAME),
    ("off_off", pools.OFF_LIST_GIVEN, pools.OFF_LIST_SURNAME),
)

PAIRS_PER_QUADRANT_PER_SHAPE = 100

# Products with a Title Case surface form, for the shapes that sit one inside a Title Case name
# sequence. `SAP` in the middle of "Carlos SAP Silva" is a different pattern entirely -- all-caps
# tokens do not join a Title Case run -- and the all-caps shapes cover it.
TITLE_PRODUCTS: tuple[str, ...] = tuple(p for p in pools.LISTED_PRODUCTS if not p.isupper())


def _pair(givens: tuple[str, ...], surnames: tuple[str, ...], index: int) -> tuple[str, str]:
    """Walks givens x surnames diagonally, so consecutive indices vary both halves.

    Deterministic on purpose: a corpus seeded from `random` reshuffles when the interpreter's
    RNG changes, and a before/after comparison across two runs then measures the shuffle.
    """
    g = givens[index % len(givens)]
    s = surnames[(index // len(givens) + index) % len(surnames)]
    return g, s


def _generated() -> list[Case]:
    cases: list[Case] = []
    for shape_index, (shape, build) in enumerate(SHAPES):
        for quadrant, givens, surnames in QUADRANTS:
            base = shape_index * PAIRS_PER_QUADRANT_PER_SHAPE
            for offset in range(PAIRS_PER_QUADRANT_PER_SHAPE):
                index = base + offset
                given, surname = _pair(givens, surnames, index)
                other = _pair(givens, surnames, index + 1)
                text, vanish, survive = build(given, surname, other, index)
                cases.append(
                    Case(
                        case_id=f"{shape}/{quadrant}/{offset:03d}",
                        shape=shape,
                        text=text,
                        must_vanish=vanish,
                        must_survive=survive,
                        tags=(quadrant,),
                    )
                )
    return cases


# Each builder returns (text, must_vanish, must_survive).
def _bare(given, surname, other, i):
    return f"{given} {surname}", (given, surname), ()


def _sentence(given, surname, other, i):
    return (
        f"{given} {surname} apresentou o plano de rollout na reuniao de ontem.",
        (given, surname),
        ("plano", "rollout", "reuniao"),
    )


def _product_before(given, surname, other, i):
    product = TITLE_PRODUCTS[i % len(TITLE_PRODUCTS)]
    return f"{product} {given} {surname} apresentou o plano.", (given, surname), (product,)


def _product_after(given, surname, other, i):
    product = TITLE_PRODUCTS[i % len(TITLE_PRODUCTS)]
    return f"{given} {surname} vai migrar o {product} ate outubro.", (given, surname), (product,)


def _product_between(given, surname, other, i):
    product = TITLE_PRODUCTS[i % len(TITLE_PRODUCTS)]
    return f"{given} {product} {surname} assumiu a entrega.", (given, surname), (product,)


def _company_before(given, surname, other, i):
    company = pools.COMPANIES[i % len(pools.COMPANIES)]
    return f"{company} {given} {surname} confirmou a renovacao.", (given, surname), (company,)


def _two_people_adjacent(given, surname, other, i):
    return (
        f"{given} {surname} {other[0]} {other[1]} fecharam o escopo.",
        (given, surname, other[0], other[1]),
        ("fecharam", "escopo"),
    )


def _at_end(given, surname, other, i):
    return (
        f"O plano de rollout foi aprovado por {given} {surname}",
        (given, surname),
        ("plano", "rollout"),
    )


def _allcaps(given, surname, other, i):
    return (
        f"{given.upper()} {surname.upper()} aprovou o escopo.",
        (given, surname),
        ("aprovou", "escopo"),
    )


def _allcaps_product_before(given, surname, other, i):
    product = pools.LISTED_PRODUCTS[i % len(pools.LISTED_PRODUCTS)]
    return (
        f"{product.upper()} {given.upper()} {surname.upper()} aprovou o escopo.",
        (given, surname),
        (product,),
    )


def _speaker_label(given, surname, other, i):
    return (
        f"{given.upper()} {surname.upper()}: fechamos o escopo.",
        (given, surname),
        ("fechamos", "escopo"),
    )


# The three shapes below exist because a review found the corpus structurally blind to the
# defect a *refusal* rule causes. Every shape above puts the product or company BEFORE, AFTER or
# BETWEEN the halves of a name, and none of them puts an ordinary or corporate word at the TAIL
# of a run that opens on a person -- which is where a rule that refuses to redact does its
# damage. Two such rules were added in this PR and the corpus punished neither: 4,482 cases
# reported zero regressions while 2,300 hand-built cases leaked.
#
# A rule that refuses to redact needs cases that make the refusal expensive. These are them.


def _name_then_company_suffix(given, surname, other, i):
    """The commonest company-name shape: a person, then a corporate word."""
    suffix = COMPANY_SUFFIXES[i % len(COMPANY_SUFFIXES)]
    return (
        f"{given} {surname} {suffix} confirmou o prazo.",
        (given, surname),
        (suffix,),
    )


def _signature_block(given, surname, other, i):
    """An attendee list or a signature block, which is most of a set of minutes.

    Only the AREA is required to survive. An earlier version of this docstring justified
    dropping the TITLE by saying Pattern 1 claims a job title together with the name it precedes
    -- which is true of "Dr. Carlos Silva" and irrelevant here, because the title FOLLOWS the
    name. `_NAME_PREFIX_RE.finditer` returns [] on this string; what swallows the title is
    Pattern 2 spanning the newline. The reason was wrong even though the decision is defensible,
    and review measured that restoring the title fails 400/400 -- so it is a REAL residual, not
    a technicality, and it is recorded as one in BASELINE.md rather than hidden behind a
    rationale that does not hold.
    """
    title = SIGNATURE_TITLES[i % len(SIGNATURE_TITLES)]
    area = COMPANY_SUFFIXES[i % len(COMPANY_SUFFIXES)]
    return (
        f"Ata assinada por\n{given} {surname}\n{title} de {area}\n",
        (given, surname),
        (area,),
    )


def _title_then_name_then_label(given, surname, other, i):
    """A job title in front and an ordinary word behind: still a person in the middle.

    The trailing label is on the NEXT LINE and belongs to no name, so it has to survive.
    `Coordenador` is not required to, for the reason given in `_signature_block`.
    """
    label = ORDINARY_TAILS[i % len(ORDINARY_TAILS)]
    return (
        f"Coordenador {given} {surname}\n{label} - apoio\n",
        (given, surname),
        (label,),
    )


# EVERY entry of `_COMPANY_TAIL_WORDS`, not a sample. Review found that twelve of the twenty
# could be deleted from the shield with zero test failures — the reachability audit above the
# list checked that each entry CAN fire and never checked that any of them is exercised. A list
# whose members no case touches is a list nobody is measuring.
# `test_every_company_tail_word_is_exercised` asserts the two stay in step.
COMPANY_SUFFIXES: tuple[str, ...] = (
    "Sistemas",
    "Solutions",
    "Solucoes",
    "Tecnologia",
    "Tecnologias",
    "Consultoria",
    "Servicos",
    "Industria",
    "Comercio",
    "Participacoes",
    "Holding",
    "Group",
    "Grupo",
    "Labs",
    "Digital",
    "Fintech",
    "Telecom",
    "Engenharia",
    "Ltda",
    "Software",
)

SIGNATURE_TITLES: tuple[str, ...] = ("Diretora", "Gerente", "Coordenador", "Diretor")

ORDINARY_TAILS: tuple[str, ...] = ("Relatorio", "Contato", "Ata", "Prazo", "Responsavel")


SHAPES = (
    ("bare", _bare),
    ("sentence", _sentence),
    ("product_before", _product_before),
    ("product_after", _product_after),
    ("product_between", _product_between),
    ("company_before", _company_before),
    ("two_people_adjacent", _two_people_adjacent),
    ("at_end", _at_end),
    ("allcaps", _allcaps),
    ("allcaps_product_before", _allcaps_product_before),
    ("speaker_label", _speaker_label),
    ("name_then_company_suffix", _name_then_company_suffix),
    ("signature_block", _signature_block),
    ("title_then_name_then_label", _title_then_name_then_label),
)


# --------------------------------------------------------------------------- #
# Negative corpus: nobody in the string, so nothing may be redacted as a person
# --------------------------------------------------------------------------- #


def _negatives() -> list[Case]:
    cases: list[Case] = []
    for i, phrase in enumerate(pools.ROLE_PHRASES):
        tokens = tuple(w for w in phrase.split() if w[:1].isupper())
        cases.append(
            Case(
                case_id=f"role_phrase/{i:03d}",
                shape="role_phrase",
                text=f"O {phrase} ficou de revisar o escopo ate sexta.",
                must_survive=tokens,
                status=KNOWN_GAP if phrase in _ROLE_PHRASES_STILL_WRONG else REQUIRED,
                note=_ROLE_PHRASES_STILL_WRONG.get(phrase, ""),
            )
        )
    for i, product in enumerate(pools.LISTED_PRODUCTS + pools.UNLISTED_PRODUCTS):
        cases.append(
            Case(
                case_id=f"product_alone/{i:03d}",
                shape="product_alone",
                text=f"A migracao do {product} entra no proximo trimestre.",
                must_survive=(product,),
            )
        )
        cases.append(
            Case(
                case_id=f"product_caps/{i:03d}",
                shape="product_alone",
                text=f"{product.upper()} SEGUE COMO PRIORIDADE DO TRIMESTRE.",
                must_survive=(product,),
            )
        )
    for i, company in enumerate(pools.COMPANIES):
        cases.append(
            Case(
                case_id=f"company_alone/{i:03d}",
                shape="company_alone",
                text=f"{company} Software Solutions renovou o contrato por doze meses.",
                must_survive=(company, "Software", "Solutions"),
            )
        )
    return cases


# --------------------------------------------------------------------------- #
# False positives: ordinary words that must not become people
#
# Three groups, and they are NOT of equal weight. Saying so here because the first version of
# this block claimed all of them priced finding 5b, and measurement said otherwise.
#
#   `fp_article` / `fp_weekday` / `fp_month` / `fp_department`
#       One Title Case token, in a sentence or bare. These assert something true and worth
#       asserting -- an ordinary noun is not a person -- but they do NOT price a loosening of
#       the single-token rule. Forcing `_is_a_name_on_its_own` to return True changes 0 of them,
#       because `_is_a_name_on_its_own` is only reachable through a pattern that needs two
#       adjacent `_TITLE_WORD` tokens, and a lone noun never gets there.
#
#   `fp_preposition`
#       A capitalised preposition in front of one of those nouns. Two Title Case tokens, so the
#       sequence pattern claims it. These fail TODAY -- `Na Sexta` comes back
#       `[[PERSON_NAME_1]]` -- and they are recorded as gaps, not as a future risk.
#
#   `fp_split_flank`
#       An allow-listed term between two ordinary Title Case words. This is the position 5b's
#       fix acts on, so this is the group that actually prices it: six of the ten break when
#       the rule is loosened, four hold. `fp_connective` (`Da Segunda`, `Do Financeiro`) prices
#       it too, and more cheaply -- correct today, and 25 of 35 break under the loosening.
#
# `test_the_false_positive_pool_is_not_inert` enforces the distinction mechanically, so the
# claim cannot drift back to the comfortable version.
# --------------------------------------------------------------------------- #


# Found by these fixtures on `main`, before any 5b work, which is what they are for.
#
# `_fold` strips accents before every list lookup, so the month folds onto the key of `marco` the
# given name. The accented spelling is now a case of its own (`fp_accent`) rather than a claim in
# a comment -- the earlier version asserted that both spellings behave alike while the corpus
# contained no accented character to check it with.
#
# Not fixed here, and deliberately not fixed by deleting `marco` from `_BR_TOP_NAMES`: it is one
# of the commonest given names in pt-BR, so that trade buys a month and sells a person. The
# signal that separates them is the temporal preposition in front (`em`, `para`, `ate`, `desde`),
# which is a different mechanism from anything 5b touches and belongs in its own change.
_MONTHS_STILL_WRONG: dict[str, str] = {
    "Marco": "accent folding collapses the month onto `marco`, a top-100 pt-BR given name, so "
    "the month is redacted as a person",
}

# `fp_preposition` was 49 of 49 failing and is now 0. The history stays because the cases only
# make sense with it.
#
# A capitalised opener and a capitalised noun are two `_TITLE_WORD` tokens, which is the shape
# `_NAME_SEQUENCE_RE` trusts, so `Na Sexta` was claimed as a person -- measured on the deployed
# worker and swept at 196 of 392 opener x noun pairs. Closed by
# `_is_an_opener_and_an_ordinary_word`, which reads two curated sets and nothing else.
#
# Two earlier attempts at the same defect were rejected on review, and `opener_then_name` exists
# because of them: both published names behind those same openers while the rates said nothing.


def _false_positives() -> list[Case]:
    cases: list[Case] = []

    for i, (article, token) in enumerate(pools.ARTICLE_TOKENS):
        cases.append(
            Case(
                case_id=f"fp_article/{i:03d}/bare",
                shape="fp_article",
                text=f"{article} {token}",
                must_survive=(token,),
            )
        )
        cases.append(
            Case(
                case_id=f"fp_article/{i:03d}/sentence",
                shape="fp_article",
                text=f"{article} {token} ficou de fora do escopo desta fase.",
                must_survive=(token,),
            )
        )

    for i, day in enumerate(pools.WEEKDAYS):
        cases.append(
            Case(
                case_id=f"fp_weekday/{i:03d}/bare",
                shape="fp_weekday",
                text=day,
                must_survive=(day,),
            )
        )
        cases.append(
            Case(
                case_id=f"fp_weekday/{i:03d}/sentence",
                shape="fp_weekday",
                text=f"Ficou combinado que {day} o time revisa o escopo.",
                must_survive=(day,),
            )
        )

    # Only segunda..sexta take `-feira`. The earlier version generated `Sabado-feira` and
    # `Domingo-feira`, which no transcript can contain, in a pool whose whole justification is
    # that its strings are real.
    for i, day in enumerate(pools.FEIRA_WEEKDAYS):
        cases.append(
            Case(
                case_id=f"fp_weekday/{i:03d}/feira",
                shape="fp_weekday",
                text=f"A entrega foi remarcada para {day}-feira.",
                must_survive=(day,),
            )
        )

    for i, month in enumerate(pools.MONTHS):
        note = _MONTHS_STILL_WRONG.get(month, "")
        status = KNOWN_GAP if note else REQUIRED
        cases.append(
            Case(
                case_id=f"fp_month/{i:03d}/bare",
                shape="fp_month",
                text=month,
                must_survive=(month,),
                status=status,
                note=note,
            )
        )
        cases.append(
            Case(
                case_id=f"fp_month/{i:03d}/sentence",
                shape="fp_month",
                text=f"O rollout foi adiado para {month} do ano que vem.",
                must_survive=(month,),
                status=status,
                note=note,
            )
        )

    for i, dept in enumerate(pools.DEPARTMENTS):
        cases.append(
            Case(
                case_id=f"fp_department/{i:03d}/bare",
                shape="fp_department",
                text=f"O {dept}",
                must_survive=(dept,),
            )
        )
        cases.append(
            Case(
                case_id=f"fp_department/{i:03d}/sentence",
                shape="fp_department",
                text=f"O {dept} pediu mais um ciclo antes de aprovar.",
                must_survive=(dept,),
            )
        )

    # Accent folding is asserted everywhere in this module and was, until now, never exercised:
    # the corpus contained no accented character at all, so "`Marco` behaves like `Marco`" was a
    # claim about two spellings written identically.
    #
    # Each accented word is put in the SAME sentence frame as its unaccented twin above, so the
    # comparison is a differential rather than two unrelated measurements. An earlier version put
    # all three in the month frame, which meant `Terca` and `Sabado` had nothing to differ from.
    _FRAMES = {
        "month": "O rollout foi adiado para {} do ano que vem.",
        "weekday": "Ficou combinado que {} o time revisa o escopo.",
    }
    for i, (accented, plain) in enumerate(pools.ACCENTED_SPELLINGS):
        note = _MONTHS_STILL_WRONG.get(plain, "")
        frame = _FRAMES["month"] if plain in pools.MONTHS else _FRAMES["weekday"]
        cases.append(
            Case(
                case_id=f"fp_accent/{i:03d}/{plain}",
                shape="fp_accent",
                text=frame.format(accented),
                must_survive=(accented,),
                status=KNOWN_GAP if note else REQUIRED,
                note=note,
            )
        )

    # The live defect. Two Title Case tokens, so the sequence pattern reaches them, and it claims
    # them: every one of these comes back `[[PERSON_NAME_1]]` today.
    for pi, prep in enumerate(pools.PREPOSITIONS):
        for ni, noun in enumerate(pools.PREPOSITION_NOUNS):
            cases.append(
                Case(
                    case_id=f"fp_preposition/{prep}/{noun}",
                    shape="fp_preposition",
                    text=f"{prep} {noun} o time revisou o escopo.",
                    must_survive=(prep, noun),
                    tags=(f"prep_{pi:02d}", f"noun_{ni:02d}"),
                )
            )

    # The group that prices finding 5b: an allow-listed term between two ordinary Title Case
    # words, which is the position the fix acts on. All ten pass today; six break when the rule
    # is forced open.
    for i, (article, first, term, second) in enumerate(pools.SPLIT_FLANK):
        cases.append(
            Case(
                case_id=f"fp_split_flank/{i:03d}",
                shape="fp_split_flank",
                text=f"{article} {first} {term} {second} entrou na pauta de ontem.",
                must_survive=(first, term, second),
            )
        )

    # The all-caps counterpart, in its own shape so it is never counted as a price-list control.
    for i, (article, first, term, second) in enumerate(pools.SPLIT_FLANK_ALLCAPS):
        cases.append(
            Case(
                case_id=f"fp_allcaps_flank/{i:03d}",
                shape="fp_allcaps_flank",
                text=f"{article} {first} {term} {second} entrou na pauta de ontem.",
                must_survive=(first, term, second),
            )
        )

    # `Da Segunda` and friends. These are on `_NAME_CONNECTIVES`, so unlike `Na`/`Em` they are
    # correct today -- but not structurally so: the sequence pattern DOES match them, and what
    # saves them is `_is_a_name_on_its_own` refusing the noun. Measured: 0 of 35 wrong today,
    # 25 of 35 break when that refusal is forced open. That makes them the cheapest cases in this
    # file -- realistic, correct now, and load-bearing the moment 5b is touched.
    for prep in pools.NAME_CONNECTIVE_PREPOSITIONS:
        for noun in pools.PREPOSITION_NOUNS:
            cases.append(
                Case(
                    case_id=f"fp_connective/{prep}/{noun}",
                    shape="fp_connective",
                    text=f"{prep} {noun} o time revisou o escopo.",
                    must_survive=(prep, noun),
                )
            )

    # The conjunction path into the same rule: `_split_on_allow_list` cuts on `e` too, so an
    # ordinary word after a real name lands in the lone-run position. The name must still go.
    cases.append(
        Case(
            case_id="fp_split_flank/conjunction",
            shape="fp_split_flank",
            text="Marina Alves e Contabilidade fecharam a apuracao.",
            must_vanish=("Marina", "Alves"),
            must_survive=("Contabilidade",),
        )
    )

    # An ordinary word in the `product_between` slot -- the mirror of the shape 5b is measured
    # on. If the fix keys on structure rather than on the name lists, these become people.
    for i, (first, second) in enumerate((("Segunda", "Contabilidade"), ("Janeiro", "Fevereiro"))):
        cases.append(
            Case(
                case_id=f"fp_split_flank/between/{i:03d}",
                shape="fp_split_flank",
                text=f"{first} Protheus {second} entraram no ciclo.",
                must_survive=(first, "Protheus", second),
            )
        )

    return cases


# Phrases the shield still reads as people. Each one degrades a summary rather than leaking a
# name, which is why they are recorded and not urgent -- and why they are not silently dropped
# from the corpus either. Adding them to `_PERSON_NAME_NEGATIVE_LIST` is the obvious fix and was
# rejected while finding 5a was live; see the finding for the trade.
_ROLE_PHRASES_STILL_WRONG: dict[str, str] = {
    "Customer Success": "two Title Case tokens, neither on any list, so the sequence pattern "
    "trusts its own shape",
    "Machine Learning": "same shape as above",
    "Pull Request": "same shape as above",
}


# --------------------------------------------------------------------------- #
# Adversarial set -- hand written, one per shape the brief names
# --------------------------------------------------------------------------- #


# Finding 5a, in two mechanisms. Both were live on `main` at the commit that introduced this
# corpus -- the measurement was committed first, as gaps, so the fix had a number to move -- and
# both are closed by `_split_on_allow_list`. The notes stay as the reason each fixture exists.
_5A_CAPS = (
    "finding 5a, mechanism A (CLOSED): `_caps_name_span` used to discard the whole all-caps run "
    "when any token was on the negative list, so the allow-listed term covered its neighbours "
    "as well as itself"
)
_5A_TITLE = (
    "finding 5a, mechanism B (CLOSED): one negative token anywhere in the candidate used to "
    "demote every run in it off the trusted path, so a name that redacts on its own stopped "
    "redacting beside a product"
)
# The residue neither mechanism explains: a run of ONE token that is on neither name list is
# refused by `_is_a_name_on_its_own`, which is the backstop that keeps "O Brasil" and "A Nota"
# from becoming people. Finding 5b is the same limitation reached without a product name.
_SINGLE_TOKEN = (
    "an allow-listed term beside one half of a name leaves a run of a single token, and a lone "
    "token on neither name list is refused by `_is_a_name_on_its_own` -- the same limitation as "
    "finding 5b, reached by a different route"
)


def _adversarial() -> list[Case]:
    return [
        # ---- product before a name: finding 5a itself ----
        Case(
            "adv/product_before/caps",
            "adv_product_before",
            "SAP CARLOS SILVA pelo apoio.",
            ("Carlos", "Silva"),
            ("SAP",),
            note=_5A_CAPS,
        ),
        Case(
            "adv/product_before/title_offlist",
            "adv_product_before",
            "Protheus Wanderleia Kranz apresentou o plano.",
            ("Wanderleia", "Kranz"),
            ("Protheus",),
            note=_5A_TITLE,
        ),
        Case(
            "adv/product_before/title_onlist",
            "adv_product_before",
            "Salesforce Wanderleia Kranz aprovou.",
            ("Wanderleia", "Kranz"),
            ("Salesforce",),
            note=_5A_TITLE,
        ),
        Case(
            "adv/product_before/caps_title",
            "adv_product_before",
            "SAP Wanderleia Kranz aprovou.",
            ("Wanderleia", "Kranz"),
            ("SAP",),
            note="an all-caps product does not join a Title Case run, so this one already passed",
        ),
        Case(
            "adv/product_before/own_product_name",
            "adv_product_before",
            "A Nora Bittencourt aprovou o escopo.",
            ("Nora", "Bittencourt"),
            (),
            status=KNOWN_GAP,
            note="the product's own name is also a given name, so `nora` on the negative "
            "list splits the pair and leaves the surname alone -- " + _SINGLE_TOKEN,
        ),
        # ---- product after a name ----
        Case(
            "adv/product_after/title",
            "adv_product_after",
            "Wanderleia Kranz vai migrar o Protheus ate outubro.",
            ("Wanderleia", "Kranz"),
            ("Protheus",),
        ),
        Case(
            "adv/product_after/caps",
            "adv_product_after",
            "CARLOS SILVA ASSUMIU O SAP.",
            ("Carlos", "Silva"),
            ("SAP",),
        ),
        # ---- product between first and last name ----
        Case(
            "adv/product_between/onlist",
            "adv_product_between",
            "Carlos Protheus Silva assumiu a entrega.",
            ("Carlos", "Silva"),
            ("Protheus",),
        ),
        Case(
            "adv/product_between/offlist",
            "adv_product_between",
            "Wanderleia Protheus Kranz assumiu a entrega.",
            ("Wanderleia", "Kranz"),
            ("Protheus",),
            status=KNOWN_GAP,
            note=_SINGLE_TOKEN,
        ),
        # ---- company before a name ----
        Case(
            "adv/company_before/title",
            "adv_company_before",
            "Acme Wanderleia Kranz confirmou a renovacao.",
            ("Wanderleia", "Kranz"),
            ("Acme",),
            note=_5A_TITLE,
        ),
        Case(
            "adv/company_before/unlisted",
            "adv_company_before",
            "TotalSys Wanderleia Kranz confirmou a renovacao.",
            ("Wanderleia", "Kranz"),
            ("TotalSys",),
        ),
        # ---- two people adjacent ----
        Case(
            "adv/two_people/adjacent",
            "adv_two_people",
            "Carlos Silva Marina Alves fecharam o escopo.",
            ("Carlos", "Silva", "Marina", "Alves"),
            (),
            note="both vanish, but into ONE placeholder -- see the fidelity test",
        ),
        Case(
            "adv/two_people/conjunction",
            "adv_two_people",
            "Osvaldo Pinheiro e Marina Alves fecharam o escopo.",
            ("Osvaldo", "Pinheiro", "Marina", "Alves"),
            (),
        ),
        Case(
            "adv/two_people/product_between",
            "adv_two_people",
            "Wanderleia Kranz Protheus Kleber Zanchetta assinaram a ata.",
            ("Wanderleia", "Kranz", "Kleber", "Zanchetta"),
            ("Protheus",),
            note=_5A_TITLE,
        ),
        # ---- a name that is also a product word ----
        Case(
            "adv/name_is_product/senior",
            "adv_name_is_product",
            "Senior Wanderleia Kranz aprovou o escopo.",
            ("Wanderleia", "Kranz"),
            (),
            note="`senior` is both an ERP vendor on the negative list and an ordinary word -- "
            + _5A_TITLE,
        ),
        Case(
            "adv/name_is_product/linear",
            "adv_name_is_product",
            "O Linear ficou com a Wanderleia Kranz.",
            ("Wanderleia", "Kranz"),
            ("Linear",),
        ),
        # ---- string edges ----
        Case(
            "adv/edge/start",
            "adv_edge",
            "Wanderleia Kranz abriu a reuniao.",
            ("Wanderleia", "Kranz"),
        ),
        Case(
            "adv/edge/end",
            "adv_edge",
            "O plano foi aprovado por Wanderleia Kranz",
            ("Wanderleia", "Kranz"),
        ),
        Case("adv/edge/only", "adv_edge", "Wanderleia Kranz", ("Wanderleia", "Kranz")),
        Case(
            "adv/edge/end_after_product",
            "adv_edge",
            "O plano do Protheus foi aprovado por Wanderleia Kranz",
            ("Wanderleia", "Kranz"),
            ("Protheus",),
        ),
        # ---- ALL CAPS ----
        Case("adv/caps/onlist", "adv_caps", "CARLOS SILVA aprovou o escopo.", ("Carlos", "Silva")),
        Case(
            "adv/caps/offlist",
            "adv_caps",
            "WANDERLEIA KRANZ aprovou o escopo.",
            ("Wanderleia", "Kranz"),
            status=KNOWN_GAP,
            note="an all-caps run with neither end on a name list is admitted only as a speaker "
            "label; in running prose it is indistinguishable from an acronym string",
        ),
        Case(
            "adv/caps/speaker_label_offlist",
            "adv_caps",
            "WANDERLEIA KRANZ: fechamos o escopo.",
            ("Wanderleia", "Kranz"),
            note="the colon and the line position carry it -- Pattern 6",
        ),
        # ---- lowercase ----
        Case(
            "adv/lowercase/onlist",
            "adv_lowercase",
            "carlos silva aprovou o escopo.",
            ("carlos", "silva"),
            status=KNOWN_GAP,
            note="deliberate: matching lowercase turns `rosa`, `clara` and `vera` -- all on the "
            "given-name list and all ordinary pt-BR words -- into people in every transcript",
        ),
        Case(
            "adv/lowercase/product_before",
            "adv_lowercase",
            "protheus carlos silva aprovou o escopo.",
            ("carlos", "silva"),
            ("protheus",),
            status=KNOWN_GAP,
            note="same deliberate gap as above; recorded so a change in either direction shows",
        ),
        # ---- the false-redaction side, in a realistic sentence ----
        Case(
            "adv/false/product_pair",
            "adv_false_redaction",
            "O SAP e o Protheus foram comparados na reuniao.",
            (),
            ("SAP", "Protheus"),
        ),
        Case(
            "adv/false/company_full",
            "adv_false_redaction",
            "Acme Software Solutions renovou o contrato por doze meses.",
            (),
            ("Acme", "Software", "Solutions"),
        ),
        Case(
            "adv/false/genitive_chain",
            "adv_false_redaction",
            "Precisamos da Lista de Campos do Protheus ate sexta.",
            (),
            ("Lista", "Campos", "Protheus"),
        ),
        # The two sides of `_COMMON_PHRASE_HEADS & _BR_TOP_SURNAMES`, which
        # `test_the_overlap_between_ordinary_and_name_vocabulary_is_recorded` pins as
        # {campos, dias}. The record says the overlap is a deliberate trade; these are what it
        # trades, so the claim is testable rather than a paragraph.
        Case(
            "adv/overlap/place_wins",
            "adv_overlap",
            "LOJA CAMPOS fechou ontem.",
            (),
            ("LOJA", "CAMPOS"),
            note="what `campos` is on the phrase-head list FOR -- a third of the surname list "
            "doubles as a place name",
        ),
        Case(
            "adv/overlap/name_loses",
            "adv_overlap",
            "Dias Silva aprovou o escopo.",
            ("Dias", "Silva"),
            (),
            status=KNOWN_GAP,
            note="the price of the line above, and it is a LEAK: `dias` heads the run, is "
            "stripped as ordinary vocabulary, one token is left and the run dies -- so a full "
            "name reaches the provider. Closing it means resolving the overlap, not patching "
            "this shape",
        ),
        Case(
            "adv/false/role_phrase",
            "adv_false_redaction",
            "O Customer Success ficou de revisar o escopo.",
            (),
            ("Customer", "Success"),
            status=KNOWN_GAP,
            note="two off-list Title Case tokens are the exact shape of a full name",
        ),
        Case(
            "adv/false/weekday",
            "adv_false_redaction",
            "Na Segunda-feira o time revisou o escopo.",
            (),
            ("Segunda",),
            note="was a gap: `Na Segunda` matched `_NAME_SEQUENCE_RE` and `Segunda` was spliced "
            "out of `Segunda-feira`, leaving `-feira` behind. Closed by "
            "`_is_an_opener_and_an_ordinary_word` -- `na` is an opener and `segunda` is on "
            "`_ORDINARY_AFTER_OPENER`, so the pair is no longer read as a name",
        ),
        # ---- both sides in one string ----
        Case(
            "adv/mixed/realistic",
            "adv_mixed",
            "Wanderleia Kranz, do Customer Success, vai migrar o Protheus com a Acme ate outubro.",
            ("Wanderleia", "Kranz"),
            ("Protheus", "Acme"),
        ),
        Case(
            "adv/mixed/minutes",
            "adv_mixed",
            "CARLOS SILVA: o SAP fica com a Wanderleia Kranz e o Jira com o Kleber Zanchetta.",
            ("Carlos", "Silva", "Wanderleia", "Kranz", "Kleber", "Zanchetta"),
            ("SAP", "Jira"),
        ),
    ]


# --------------------------------------------------------------------------- #
# A name ALONE behind a phrase head
#
# The corpus has 5,628 leak-scope cases and not one of them is this shape, because every
# generated builder emits `{given} {surname}` as a pair. So `Com Silva aprovou o escopo.` had
# never been asked, and it leaks: 78 of 78 head x surname combinations publish the name on
# `main` today.
#
# These go in as gaps, with `main`'s behaviour as measured. They are the reason the leak ceiling
# rises in the commit that adds them, and the shield is untouched there -- the rate moves because
# the measurement stopped being blind, not because anything got worse.
# --------------------------------------------------------------------------- #

_LONE_NAME_GAP = (
    "`_COMMON_PHRASE_HEADS` is consumed by two rules that discard the whole run once the head is "
    "stripped and one token is left: `_trusted_span` gives up below two tokens, and "
    "`_qualify_run` returns on `stripped_a_label` before it reaches the lone-token lookup that "
    "recognises a surname. The label in front decides, which is exactly what the comment above "
    "that lookup says it must not do"
)


def _lone_name_after_head() -> list[Case]:
    cases: list[Case] = []
    for head in pools.LEAK_HEADS:
        for surname in pools.LEAK_SURNAMES:
            cases.append(
                Case(
                    case_id=f"lone_name_after_head/{head}/{surname}",
                    shape="lone_name_after_head",
                    text=f"{head} {surname} aprovou o escopo.",
                    must_vanish=(surname,),
                    must_survive=(head,),
                    status=KNOWN_GAP,
                    note=_LONE_NAME_GAP,
                )
            )
    return cases


# --------------------------------------------------------------------------- #
# The two shapes that priced two rejected changes, neither of which the rates could see
#
# Both pass on `main` -- measured at 0 of 48 and 0 of 40 before these existed. They are not here
# to record a defect; they are here so that the next attempt at the sentence-opener defect fails
# a test instead of a review.
#
# `opener_then_name`      a name behind a sentence opener MUST still vanish.
#                         Broken by putting the openers on `_COMMON_PHRASE_HEADS`, and again by
#                         a rule that read that set to mean "ordinary word" when two of its
#                         members are surnames.
#
# `place_head_then_tail`  an address MUST NOT become a person.
#                         Broken by narrowing `stripped_a_label`, which made `Galpao Prado`
#                         redact while `GALPAO PRADO` did not -- the case-invariance property
#                         `test_pii_shield.py` pins.
# --------------------------------------------------------------------------- #


def _opener_and_place_shapes() -> list[Case]:
    cases: list[Case] = []
    # `must_vanish` only, and the omission is deliberate.
    #
    # The opener does NOT survive today: `Depois Silva aprovou o escopo.` comes back
    # `[[PERSON_NAME_1]] aprovou o escopo.`, with `Depois` inside the placeholder. Measured at
    # 48 of 48. That is a real if minor false redaction and it is pre-existing.
    #
    # It is not asserted here because the harness scores a case pass/fail as a whole, not per
    # direction. Adding `must_survive=(opener,)` would make every one of these fail on the
    # opener, so they would have to be KNOWN_GAP -- and a KNOWN_GAP is asserted to FAIL, which
    # would stop pinning the thing they exist for: that the NAME still vanishes. A case that
    # fails for a second reason cannot guard the first one.
    for opener in pools.SENTENCE_OPENERS:
        for name in pools.OPENER_NAMES:
            cases.append(
                Case(
                    case_id=f"opener_then_name/{opener}/{name}",
                    shape="opener_then_name",
                    text=f"{opener} {name} aprovou o escopo.",
                    must_vanish=(name,),
                )
            )
    for place in pools.PLACE_HEADS:
        for tail in pools.PLACE_TAILS:
            cases.append(
                Case(
                    case_id=f"place_head_then_tail/{place}/{tail}",
                    shape="place_head_then_tail",
                    text=f"{place} {tail} entrou na pauta.",
                    must_survive=(place, tail),
                )
            )
    return cases


def all_cases() -> list[Case]:
    """The whole corpus, generated and hand written, in a stable order."""
    return (
        _generated()
        + _negatives()
        + _false_positives()
        + _lone_name_after_head()
        + _opener_and_place_shapes()
        + _adversarial()
    )


def false_positive_cases() -> list[Case]:
    return _false_positives()


def lone_name_after_head_cases() -> list[Case]:
    return _lone_name_after_head()


def opener_and_place_cases() -> list[Case]:
    return _opener_and_place_shapes()


def adversarial_cases() -> list[Case]:
    return _adversarial()
