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

    Only the AREA is required to survive, not the title. A job title directly attached to a name
    is claimed with it by Pattern 1 -- "Dr. Carlos Silva" is one span by design and has been
    since the module was written -- and smuggling that older question in here would make this
    shape permanently red for a reason unrelated to what it tests.
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


COMPANY_SUFFIXES: tuple[str, ...] = (
    "Sistemas",
    "Solutions",
    "Tecnologia",
    "Consultoria",
    "Digital",
    "Engenharia",
    "Servicos",
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
            status=KNOWN_GAP,
            note="`Segunda` is spliced out of `Segunda-feira`, leaving `-feira` behind",
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


def all_cases() -> list[Case]:
    """The whole corpus, generated and hand written, in a stable order."""
    return _generated() + _negatives() + _adversarial()


def adversarial_cases() -> list[Case]:
    return _adversarial()
