"""PII Shield baseline: deterministic regex applied before any call to the LLM.

Covers the basic Brazilian types (e-mail, phone, CPF, CNPJ, card) and detects
proper names via heuristic regex + hardcoded list of top BR names + negative list
of technical/product/company terms. ADDRESS is left for a future slice.

The PERSON_NAME strategy is detailed in the docstring of `_redact_person_names`.
"""

from __future__ import annotations

import hashlib
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from ..models import PiiRedactionV1, PiiType, Redaction


def _fold(value: str) -> str:
    """Normalizes for ACCENT- and case-insensitive comparison (NFKD + casefold).

    CRITICAL for PERSON_NAME: the `_BR_TOP_NAMES` list is written WITHOUT accents,
    but real transcripts bring accented names (Patrícia, Antônio, André, João).
    Without the accent-fold, "Patrícia".casefold() ("patrícia") never matched
    "Patricia".casefold() ("patricia") from the list -> the name leaked raw to the LLM.
    Real bug found in production (jun/2026). ADR 0012.
    """
    return "".join(
        c for c in unicodedata.normalize("NFKD", value) if not unicodedata.combining(c)
    ).casefold()


# --------------------------------------------------------------------------- #
# Deterministic patterns: e-mail, CPF, CNPJ, card, phone
# --------------------------------------------------------------------------- #

# Email with (?<!\w) anchoring on the left avoids catastrophic backtracking on
# large inputs (measured empirically: 100KB of input was ~10s; with the anchor
# it hits microseconds). Pre-filter `'@' in text` speeds up inputs with no email.
_EMAIL_RE = re.compile(r"(?<![\w@])[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b")

# BR phones: DDD mandatory (8 or 9 digits after the DDD). Conservative
# on purpose: a phone without DDD (98765-4321) is too small to tell apart
# from numeric codes/protocols without massive false-positives.
#
# Tolerances (audit 2026-06-16, ADR 0012) -- all WITHOUT relaxing the
# DDD requirement (a phone has no check digit, so a mandatory DDD holds the FP):
#   - `(?:\+?55[\s.\-]?)?`        optional international +55 prefix.
#   - `\(?\s*0?\d{2}\s*\)?`       parentheses with inner space ("( 11 )") and
#                                 DDD with the old 3-digit zero ("(011)").
#   - `(?:9[\s.\-/]?)?`           mobile 9th digit dictated LOOSE between the DDD
#                                 and the number ("(11) 9 8765-4321") -- common in
#                                 speech-to-text transcription.
#   - `[\s.\-/]`                  separator `/` ("11/98765/4321") besides space/
#                                 dot/hyphen.
# DEFERRED (high FP risk without a check digit -- left for a future slice):
#   - phone WITHOUT DDD ("99988-7766", "3003-1234"): too small to tell apart
#     from numeric codes/protocols.
#   - non-BR international ("+1 415 555 2671"): generalizing `\+\d{1,3}` explodes FP.
_PHONE_RE = re.compile(
    r"(?<!\d)(?:\+?55[\s.\-]?)?\(?\s*0?\d{2}\s*\)?[\s.\-/]?(?:9[\s.\-/]?)?\d{4,5}[\s.\-/]?\d{4}(?!\d)"
)

# Masked CPF.
_CPF_RE = re.compile(r"(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")
# Raw CPF (11 digits, no mask). Check-digit validation in `_validate_cpf` filters
# false positives (e.g.: 12345678900 does not pass).
_CPF_RAW_RE = re.compile(r"(?<!\d)\d{11}(?!\d)")
# Partially masked CPF (hyphen only, no dots): "12345678-09".
_CPF_PARTIAL_RE = re.compile(r"(?<!\d)\d{8}-\d{2}(?!\d)")
# CPF with groups separated by SPACE: "111 444 777 35" (3-3-3-2). The check-digit
# validation (after stripping the spaces) avoids redacting random numeric sequences.
_CPF_SPACED_RE = re.compile(r"(?<!\d)\d{3}\s\d{3}\s\d{3}\s\d{2}(?!\d)")
# CPF TOLERANT to arbitrary separators (audit 2026-06-16): 11 digits
# in 3-3-3-2 groups with ANY separator from the class `[.\-/\s]` between each group.
# Covers "111.444.777 35" (mixed), "111/444/777-35" (slash) and "111-444-777-35"
# (hyphen only) -- formats the rigid patterns above did not match. The check digit
# (`_validate_cpf_separated`) is the gate: a tolerant regex only becomes a redaction
# if the check digit closes, so the FP on numeric sequences is ~zero.
# Superset of the patterns above (kept for clarity/regression; overlap-skip dedupe).
_CPF_SEP_RE = re.compile(r"(?<!\d)\d{3}[.\-/\s]\d{3}[.\-/\s]\d{3}[.\-/\s]\d{2}(?!\d)")

# Masked CNPJ.
_CNPJ_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}(?!\d)")
# Raw CNPJ (14 digits, no mask). Check-digit validation filters false positives.
_CNPJ_RAW_RE = re.compile(r"(?<!\d)\d{14}(?!\d)")
# CNPJ with groups separated by SPACE: "11 222 333 0001 81" (2-3-3-4-2). The
# check-digit validation (after stripping the spaces) filters false positives.
_CNPJ_SPACED_RE = re.compile(r"(?<!\d)\d{2}\s\d{3}\s\d{3}\s\d{4}\s\d{2}(?!\d)")
# CNPJ with a DOT between ALL groups: "11.222.333.0001.81" (2.3.3.4.2). The
# patterns above require `/` + `-` (canonical mask) or space; "dots only" did not
# match (audit 2026-06-16). The check digit (`_validate_cnpj_separated`) is the gate.
_CNPJ_DOTS_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}\.\d{4}\.\d{2}(?!\d)")

# Cards — Amex has 15 digits with prefix 34/37; the rest have 16 as 4x4.
# Accepted separators: space, hyphen and DOT ("4111.1111.1111.1111"). The
# Luhn validation (`_validate_card`, after stripping separators) drastically reduces
# the false-positive rate of any generic 16-digit sequence.
_CARD_AMEX_RE = re.compile(r"(?<!\d)3[47]\d{2}[\s.\-]?\d{6}[\s.\-]?\d{5}(?!\d)")
_CARD_RE = re.compile(r"(?<!\d)(?:\d{4}[\s.\-]?){3}\d{4}(?!\d)")
# Diners Club (and some UnionPay): 14 digits in 4-4-4-2 groups
# ("3056 9309 0259 04"). The generic _CARD_RE only matches 16 digits (4x4); this one
# covers length 14. Luhn (`_validate_card`, now accepting len 14) is the
# gate. The `(?!\d)` anchor prevents matching a prefix of a 16-digit card.
_CARD_DINERS_RE = re.compile(r"(?<!\d)\d{4}[\s.\-]?\d{4}[\s.\-]?\d{4}[\s.\-]?\d{2}(?!\d)")


def _validate_cpf(digits: str) -> bool:
    """Validates 11-digit CPF via check digit. Rejects trivial sequences (000.000.000-00 etc.)."""
    if len(digits) != 11 or not digits.isdigit():
        return False
    if digits == digits[0] * 11:  # 000... to 999... — invalid
        return False
    for j in (9, 10):
        s = sum(int(digits[i]) * (j + 1 - i) for i in range(j))
        d = (s * 10) % 11
        if d == 10:
            d = 0
        if d != int(digits[j]):
            return False
    return True


def _validate_cnpj(digits: str) -> bool:
    """Validates a 14-digit CNPJ via check digit."""
    if len(digits) != 14 or not digits.isdigit():
        return False
    if digits == digits[0] * 14:
        return False
    weights1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    weights2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    for j, weights in ((12, weights1), (13, weights2)):
        s = sum(int(digits[i]) * weights[i] for i in range(j))
        d = s % 11
        d = 0 if d < 2 else 11 - d
        if d != int(digits[j]):
            return False
    return True


def _luhn_ok(digits: str) -> bool:
    """Checks the Luhn check digit (mod 10) of a digit sequence."""
    total = 0
    for i, ch in enumerate(reversed(digits)):
        n = int(ch)
        if i % 2 == 1:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return total % 10 == 0


def _strip_separators(value: str) -> str:
    """Removes everything that is not a digit (space, dot, hyphen, slash)."""
    return re.sub(r"\D", "", value)


def _validate_card(value: str) -> bool:
    """Validates a card candidate: 14 (Diners), 15 (Amex) or 16 digits + Luhn.

    Receives the raw match (with space/dot/hyphen separators) and normalizes before
    the Luhn. Reduces false-positives from any generic 14-16 digit sequence
    (order codes, tracking, NFE) that does not pass the check digit.
    Length 14 covers Diners Club and some UnionPay ranges (audit 2026-06-16).
    """
    digits = _strip_separators(value)
    if len(digits) not in (14, 15, 16):
        return False
    return _luhn_ok(digits)


def _validate_cpf_separated(value: str) -> bool:
    """Validates CPF whose match has separators (space): normalizes and checks the check digit."""
    return _validate_cpf(_strip_separators(value))


def _validate_cnpj_separated(value: str) -> bool:
    """Validates CNPJ whose match has separators (space): normalizes and checks the check digit."""
    return _validate_cnpj(_strip_separators(value))


# Order matters: masked/cards first (more specific regex), then raw
# with check-digit check (raw CPF/CNPJ), phone last (most ambiguous). Without that
# order, PHONE_RE eats 11 digits before CPF_RAW_RE can identify it — wrong type
# in the placeholder but the same degree of protection.
#
# `_apply_basic_patterns` filters raw/separated via `_validate_*`. Cards require
# a valid Luhn (`_validate_card`). The Amex card (15 digits) comes BEFORE the generic
# _CARD_RE (16 digits with 4x4). The space-separated patterns (CPF/CNPJ) come
# together with the masked ones, but are only accepted with a valid check digit.
_BASIC_PATTERNS: list[tuple[PiiType, re.Pattern[str]]] = [
    (PiiType.EMAIL, _EMAIL_RE),
    (PiiType.CPF, _CPF_RE),
    (PiiType.CPF, _CPF_PARTIAL_RE),
    (PiiType.CPF, _CPF_SPACED_RE),  # "111 444 777 35" — requires check digit
    # "111.444.777 35" / "111/444/777-35" / "111-444-777-35" — requires check digit
    (PiiType.CPF, _CPF_SEP_RE),
    (PiiType.CNPJ, _CNPJ_RE),
    (PiiType.CNPJ, _CNPJ_SPACED_RE),  # "11 222 333 0001 81" — requires check digit
    (PiiType.CNPJ, _CNPJ_DOTS_RE),  # "11.222.333.0001.81" — requires check digit
    (PiiType.CREDIT_CARD, _CARD_AMEX_RE),
    (PiiType.CREDIT_CARD, _CARD_RE),
    (PiiType.CREDIT_CARD, _CARD_DINERS_RE),  # "3056 9309 0259 04" (14 dig) — requires Luhn
    (PiiType.CNPJ, _CNPJ_RAW_RE),  # raw with check digit — before PHONE to gain priority
    (PiiType.CPF, _CPF_RAW_RE),
    (PiiType.PHONE, _PHONE_RE),
]

# Patterns that require validation (check digit or Luhn) to be accepted. Cards use
# Luhn; CPF/CNPJ (raw or space-separated) use the check digit.
_VALIDATORS: dict[int, callable] = {
    id(_CPF_RAW_RE): _validate_cpf,
    id(_CNPJ_RAW_RE): _validate_cnpj,
    id(_CPF_SPACED_RE): _validate_cpf_separated,
    id(_CPF_SEP_RE): _validate_cpf_separated,
    id(_CNPJ_SPACED_RE): _validate_cnpj_separated,
    id(_CNPJ_DOTS_RE): _validate_cnpj_separated,
    id(_CARD_AMEX_RE): _validate_card,
    id(_CARD_RE): _validate_card,
    id(_CARD_DINERS_RE): _validate_card,
}


# --------------------------------------------------------------------------- #
# PERSON_NAME -- lists and patterns
# --------------------------------------------------------------------------- #

# Top Brazilian first names (mix of male + female + common variants).
# Curated from IBGE/Census surveys + names recurring in pt-BR corporate
# transcripts. Finite list: each term works as an anchor to trigger the
# "isolated name" pattern (without surname). Surnames do not need to be here --
# `_NAME_SEQUENCE_RE` covers "Marina Alves" via Title Case.
_BR_TOP_NAMES: frozenset[str] = frozenset(
    _fold(name)
    for name in (
        # Male
        "Adriano",
        "Alessandro",
        "Alex",
        "Alexandre",
        "Alvaro",
        "Andre",
        "Antonio",
        "Arthur",
        "Augusto",
        "Benjamin",
        "Bernardo",
        "Breno",
        "Bruno",
        "Caio",
        "Camilo",
        "Carlos",
        "Cesar",
        "Claudio",
        "Daniel",
        "Danilo",
        "Dario",
        "Davi",
        "David",
        "Diego",
        "Diogo",
        "Douglas",
        "Eduardo",
        "Elias",
        "Emanuel",
        "Enzo",
        "Eric",
        "Erick",
        "Estevao",
        "Everton",
        "Fabio",
        "Fabricio",
        "Felipe",
        "Fernando",
        "Filipe",
        "Flavio",
        "Francisco",
        "Frederico",
        "Gabriel",
        "Geraldo",
        "Gilberto",
        "Giovanni",
        "Guilherme",
        "Gustavo",
        "Heitor",
        "Helio",
        "Henrique",
        "Hugo",
        "Igor",
        "Ivan",
        "Jair",
        "Joao",
        "Joaquim",
        "Jorge",
        "Jose",
        "Juan",
        "Juliano",
        "Julio",
        "Junior",
        "Kaique",
        "Kevin",
        "Leandro",
        "Leonardo",
        "Levi",
        "Lucas",
        "Luciano",
        "Lucio",
        "Luis",
        "Luiz",
        "Marcelo",
        "Marcio",
        "Marco",
        "Marcos",
        "Mario",
        "Mateus",
        "Matheus",
        "Mauricio",
        "Maximo",
        "Miguel",
        "Murilo",
        "Nathan",
        "Nelson",
        "Nicolas",
        "Olavo",
        "Otavio",
        "Paulo",
        "Pedro",
        "Rafael",
        "Raimundo",
        "Ramon",
        "Ramiro",
        "Raul",
        "Renan",
        "Renato",
        "Ricardo",
        "Roberto",
        "Robson",
        "Rodrigo",
        "Rogerio",
        "Romero",
        "Ronaldo",
        "Ruan",
        "Rubens",
        "Samuel",
        "Sandro",
        "Saulo",
        "Sebastiao",
        "Sergio",
        "Silas",
        "Silvio",
        "Tadeu",
        "Tales",
        "Thiago",
        "Tiago",
        "Tomas",
        "Ulisses",
        "Valter",
        "Vicente",
        "Victor",
        "Vinicius",
        "Vitor",
        "Wagner",
        "Wallace",
        "Wanderson",
        "Washington",
        "Wellington",
        "Wesley",
        "William",
        "Yago",
        "Yan",
        "Yuri",
        # Female
        "Adriana",
        "Alessandra",
        "Alice",
        "Aline",
        "Amanda",
        "Ana",
        "Andrea",
        "Andressa",
        "Angela",
        "Antonia",
        "Beatriz",
        "Bianca",
        "Bruna",
        "Camila",
        "Carla",
        "Carmen",
        "Carolina",
        "Catarina",
        "Cecilia",
        "Celia",
        "Cintia",
        "Clara",
        "Claudia",
        "Cristiane",
        "Cristina",
        "Daiane",
        "Daniela",
        "Debora",
        "Diana",
        "Eduarda",
        "Elaine",
        "Elen",
        "Eliane",
        "Elisa",
        "Elisabete",
        "Eloisa",
        "Emanuela",
        "Emilia",
        "Erica",
        "Esther",
        "Eva",
        "Fabiana",
        "Fatima",
        "Fernanda",
        "Flavia",
        "Francisca",
        "Gabriela",
        "Geovana",
        "Giovana",
        "Giselle",
        "Gloria",
        "Helena",
        "Heloisa",
        "Ines",
        "Ingrid",
        "Isabel",
        "Isabela",
        "Isadora",
        "Janaina",
        "Jaqueline",
        "Jessica",
        "Joana",
        "Josiane",
        "Juliana",
        "Julia",
        "Karen",
        "Karina",
        "Karla",
        "Katia",
        "Larissa",
        "Laura",
        "Leandra",
        "Leila",
        "Leticia",
        "Lia",
        "Lidia",
        "Liliane",
        "Livia",
        "Lorena",
        "Luana",
        "Lucia",
        "Luciana",
        "Luiza",
        "Manuela",
        "Marcela",
        "Marcia",
        "Margarida",
        "Maria",
        "Mariana",
        "Marilia",
        "Marina",
        "Marta",
        "Michele",
        "Milena",
        "Monica",
        "Natalia",
        "Nathalia",
        "Nicole",
        "Olivia",
        "Patricia",
        "Paula",
        "Priscila",
        "Rafaela",
        "Raquel",
        "Rebeca",
        "Regina",
        "Renata",
        "Roberta",
        "Rosa",
        "Rosana",
        "Rute",
        "Sabrina",
        "Sandra",
        "Sara",
        "Sheila",
        "Silvia",
        "Simone",
        "Sofia",
        "Solange",
        "Sonia",
        "Stella",
        "Suzana",
        "Tais",
        "Talita",
        "Tamara",
        "Tatiana",
        "Teresa",
        "Thais",
        "Vanessa",
        "Vera",
        "Veronica",
        "Vitoria",
        "Viviane",
        "Yara",
        "Yasmin",
        "Zelia",
    )
)

# Terms that look like names (Title Case or first name) but designate
# products, companies, technologies, frameworks or concepts -- never redact.
# Used as a post-match filter: if any token of the candidate hits here
# (case-insensitive), the whole match is discarded.
_PERSON_NAME_NEGATIVE_LIST: frozenset[str] = frozenset(
    _fold(term)
    for term in (
        # TOTVS products and related
        "TOTVS",
        "Protheus",
        "RM",
        "Fluig",
        "Logix",
        "Datasul",
        "Backoffice",
        # Competing ERP/CRM
        "SAP",
        "Oracle",
        "Senior",
        "Sankhya",
        "Salesforce",
        "HubSpot",
        "Pipedrive",
        "Zoho",
        "Microsoft",
        "Dynamics",
        "NetSuite",
        "Workday",
        "ServiceNow",
        # Big tech / cloud
        "Azure",
        "Google",
        "Amazon",
        "AWS",
        "GCP",
        "Apple",
        "Meta",
        "Facebook",
        "OpenAI",
        "Anthropic",
        "Claude",
        "Gemini",
        "Copilot",
        # Dev tools
        "GitHub",
        "GitLab",
        "Bitbucket",
        "Jira",
        "Linear",
        "Notion",
        "Slack",
        "Teams",
        "Zoom",
        "Figma",
        "Postman",
        "Docker",
        "Kubernetes",
        "Jenkins",
        # Technical stack (Oracle already listed under "Competing ERP/CRM" above)
        "Python",
        "Java",
        "Spring",
        "React",
        "Next",
        "Node",
        "Angular",
        "Vue",
        "Tauri",
        "Pydantic",
        "Postgres",
        "MySQL",
        "Redis",
        "Kafka",
        # NORA product/company
        "NORA",
        "Nora",
        "Acme",
        # Generic terms that may appear Title Case and are not names
        "Sprint",
        "Backlog",
        "MVP",
        "POC",
        "CRM",
        "ERP",
        "API",
        "SDK",
        "JSON",
        "XML",
        "HTML",
        "CSS",
        "SQL",
        "URL",
        "JWT",
        "SSO",
        "MFA",
        "GDPR",
        "LGPD",
        "SOC",
        "ISO",
    )
)


# Letters accepted inside a name token.
#
# These used to be a hand-written list of the PT-BR accents (`ÁÉÍÓÚÂÊÔÀÃÕÇ` /
# `áéíóúâêôàãõç`). Brazil is full of surnames that do not fit in it -- German
# (Schürmann, Müller), Spanish (Núñez, Peña), Nordic (Sjöberg) -- and the effect was
# not merely missing them: `[a-záéíóúâêôàãõç]+` stopped AT the foreign letter, so
# "Eng. Schürmann" matched as "Eng. Sch" and the placeholder was spliced into the
# middle of the surname -> "[[PERSON_NAME_1]]ürmann", which leaks the tail in the clear
# AND corrupts the text sent to the model. The ranges below are the whole Latin-1
# letter block; U+00D7 and U+00F7 fall outside them by construction, being the
# multiplication and division signs and not letters. `_fold` normalizes with NFKD,
# so `ü` still compares equal to `u`.
_UPPER = "A-ZÀ-ÖØ-Þ"
_LOWER = "a-zß-öø-ÿ"

_TITLE_WORD = f"[{_UPPER}][{_LOWER}]+"

# Prefix (honorifics + job titles) followed by 1-5 Title Case words,
# supporting PT-BR connectives (`da`, `de`, `do`, `das`, `dos`, `e`).
# Captures "Dr. Carlos Silva", "Sra. Marina Alves", "Profa. Ana de Souza",
# "Sr. Jose da Silva Pereira", "Eng. Joao", "Diretor Carlos da Silva", etc.
#
# The closing `\b` is not decoration: without it the match can still end inside a word
# whenever the following letter falls outside `_LOWER` (`Ł`, `Š`, any non-Latin script),
# and an end-of-match in mid-word is exactly what produces a spliced placeholder. With
# the `\b` the engine backtracks to the last whole word instead -- it redacts less, never
# a fragment. `_spans_without_negatives` re-checks the same invariant against the full
# text, because the regex alone cannot see past its own match.
_NAME_PREFIX_RE = re.compile(
    r"\b(?:Sr|Sra|Srta|Dr|Dra|Prof|Profa|Eng|Engenheiro|Engenheira|"
    r"Cap|Sgt|Gen|Pe|Padre|Diretor|Diretora|Coordenador|Coordenadora|"
    r"Gerente|Pres|Presidente)\.?\s+"
    f"{_TITLE_WORD}"
    f"(?:\\s+(?:d[aeo]s?|e)\\s+{_TITLE_WORD}"
    f"|\\s+{_TITLE_WORD}){{0,4}}\\b"
)

# 2-5 consecutive Title Case words, with support for PT-BR connectives
# (`da`, `de`, `do`, `das`, `dos`, `e`) between tokens. "Jose da Silva Pereira"
# matches as a single compound name instead of two separate ones. All-caps
# tokens ("TOTVS", "RM") are ignored for not having lowercase at the end --
# avoids capturing acronyms as part of a compound name.
_NAME_SEQUENCE_RE = re.compile(
    f"\\b{_TITLE_WORD}(?:\\s+(?:d[aeo]s?|e)\\s+{_TITLE_WORD}|\\s+{_TITLE_WORD}){{1,4}}\\b"
)

# Generic alphabetic token (used by `_tokenize` for the negative list — it can
# be case-insensitive because we only check against the negative list, which also
# uses casefold).
_WORD_RE = re.compile(f"[{_UPPER}{_LOWER}]+")

# Isolated BR first name: ONLY matches Title Case (`Joao`, `Marina`). Matching
# lowercase (`joao`, `rosa`, `clara`) produces massive false-positives on
# common PT-BR nouns. In real corporate transcripts, first
# names always appear Title Case (automatic capitalization by the dictation) or
# all-caps in formal context (which are filtered by `_PERSON_NAME_NEGATIVE_LIST`).
_NAME_TOKEN_RE = re.compile(f"\\b{_TITLE_WORD}\\b")


# --------------------------------------------------------------------------- #
# Dataclasses and utilities
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class _Match:
    type: PiiType
    start: int
    end: int
    value: str


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _tokenize(value: str) -> list[str]:
    """Breaks a name candidate into alphabetic tokens (discards prefixes with a dot)."""
    return [tok for tok in _WORD_RE.findall(value)]


def _is_negative(value: str) -> bool:
    """Returns True if any token of the candidate hits the negative list."""
    return any(_fold(tok) in _PERSON_NAME_NEGATIVE_LIST for tok in _tokenize(value))


_NAME_CONNECTIVES: frozenset[str] = frozenset(
    _fold(c) for c in ("da", "de", "do", "das", "dos", "e")
)

# Honorifics and job titles accepted by `_NAME_PREFIX_RE`. Repeated here as a
# set because, in the trimming path below, the prefix is itself the signal that the
# remaining stretch is a person ("Dr. Carlos" still holds after removing a product).
_NAME_HONORIFICS: frozenset[str] = frozenset(
    _fold(h)
    for h in (
        "Sr",
        "Sra",
        "Srta",
        "Dr",
        "Dra",
        "Prof",
        "Profa",
        "Eng",
        "Engenheiro",
        "Engenheira",
        "Cap",
        "Sgt",
        "Gen",
        "Pe",
        "Padre",
        "Diretor",
        "Diretora",
        "Coordenador",
        "Coordenadora",
        "Gerente",
        "Pres",
        "Presidente",
    )
)

# Subset that only appears before a PERSON. Job titles are left out on purpose:
# "Gerente de Contas", "Diretor Comercial" and "Presidente do Conselho" are roles that exist
# with nobody in the middle, and accepting them as a person signal in the trimming path turned
# a job-title phrase into a PERSON_NAME -- with the title's hash in the redaction record.
# The most frequent Brazilian surnames (IBGE ordering, roughly). Used only as the TAIL signal in
# `_qualify_run`: a Title Case stretch ending in one of these is a person, even when its first
# token is outside the 300 given names in `_BR_TOP_NAMES`. Deliberately surnames only -- adding
# given names here would just duplicate that list, and adding rare surnames buys little recall
# while raising the chance of colliding with a company name.
_BR_TOP_SURNAMES: frozenset[str] = frozenset(
    _fold(s)
    for s in (
        "Silva",
        "Santos",
        "Oliveira",
        "Souza",
        "Sousa",
        "Rodrigues",
        "Ferreira",
        "Alves",
        "Pereira",
        "Lima",
        "Gomes",
        "Ribeiro",
        "Carvalho",
        "Almeida",
        "Lopes",
        "Soares",
        "Fernandes",
        "Vieira",
        "Barbosa",
        "Rocha",
        "Dias",
        "Nascimento",
        "Andrade",
        "Moreira",
        "Nunes",
        "Marques",
        "Machado",
        "Mendes",
        "Freitas",
        "Cardoso",
        "Ramos",
        "Goncalves",
        "Santana",
        "Teixeira",
        "Araujo",
        "Correia",
        "Cavalcante",
        "Monteiro",
        "Moura",
        "Cunha",
        "Pinto",
        "Duarte",
        "Medeiros",
        "Castro",
        "Campos",
        "Batista",
        "Miranda",
        "Farias",
        "Pires",
        "Reis",
        "Melo",
        "Cruz",
        "Azevedo",
        "Coelho",
        "Borges",
        "Xavier",
        "Aguiar",
        "Bezerra",
        "Macedo",
        "Tavares",
        "Guimaraes",
        "Assuncao",
        "Fonseca",
        "Peixoto",
        "Sampaio",
        "Braga",
        "Amaral",
        "Neves",
        "Leite",
        "Rezende",
        "Siqueira",
        "Antunes",
        "Bastos",
        "Camargo",
        "Caldeira",
        "Sales",
        "Prado",
        "Padilha",
        "Rangel",
        "Vasconcelos",
        "Nogueira",
        # Added after a review: the list was missing names from the IBGE TOP 10 --
        # "Costa" and "Martins" among them --, so "Edson Costa" had no tail signal and
        # left in the clear. Their absence was not a judgement call, it was an oversight.
        "Costa",
        "Martins",
        "Barros",
        "Pinheiro",
        "Silveira",
        "Correa",
        "Magalhaes",
        "Brandao",
        "Cavalcanti",
        "Maia",
        "Viana",
        "Brito",
        "Queiroz",
        "Pacheco",
        "Figueiredo",
        "Barreto",
        "Mota",
        "Motta",
        "Amorim",
        "Paiva",
    )
)

_PERSON_ONLY_HONORIFICS: frozenset[str] = frozenset(
    _fold(h)
    for h in (
        "Sr",
        "Sra",
        "Srta",
        "Dr",
        "Dra",
        "Prof",
        "Profa",
        "Eng",
        "Engenheiro",
        "Engenheira",
        "Cap",
        "Sgt",
        "Gen",
        "Pe",
        "Padre",
    )
)


def _spans_without_negatives(value: str, offset: int, text: str) -> list[tuple[int, int]]:
    """All stretches of the candidate that still hold as a name, left to right.

    Returns a list because a negative token in the MIDDLE separates two distinct names: in
    "Ana Souza Protheus Carlos Silva" two clean stretches remain, and the previous version --
    which kept only the longest via `max()`, the first one on a tie -- discarded
    "Carlos Silva" entirely. The surname went out in the clear.
    """
    runs: list[list[re.Match[str]]] = []
    current: list[re.Match[str]] = []
    for tok in _WORD_RE.finditer(value):
        if _fold(tok.group(0)) in _PERSON_NAME_NEGATIVE_LIST:
            if current:
                runs.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        runs.append(current)

    if len(runs) <= 1 and not any(
        _fold(t.group(0)) in _PERSON_NAME_NEGATIVE_LIST for t in _WORD_RE.finditer(value)
    ):
        # No negative token at all: the match passes whole, as it always did. It is NOT
        # re-qualified -- an unsplit match is trusted because the regex itself is the signal,
        # and demanding a known given name or surname here would drop every real name outside
        # the two lists ("Kleber Zanchetta").
        #
        # The one thing still checked is the boundary. This branch used to return the span
        # blind, and that is how "Eng. Schürmann" -- matched as "Eng. Sch" back when `_LOWER`
        # could not see `ü` -- had its placeholder spliced into the middle of the surname. The
        # regex now ends on a `\b`, so this is the second lock on the same door: any future
        # letter outside `_LOWER` cuts the match short again, and here we refuse the cut
        # rather than emit a fragment.
        if not runs:
            return []
        # Tightened BEFORE the boundary is judged. The span handed in by `_claim_free_parts`
        # ends wherever the covered neighbour begins, so it carries the separating space, and
        # judging that end would read the neighbour's first letter as "mid-word" and drop a
        # perfectly good name: "Ribeiro Alves Dr. Ana" lost "Ribeiro Alves" that way.
        span = _tighten_to_tokens(offset, offset + len(value), text)
        if span is None:
            return []
        if _is_a_genitive_chain(runs[0]):
            # Not trusted after all -- fall through to the same qualification the split path
            # uses. See `_is_a_genitive_chain`. Leaving this out made the decision depend on
            # whether a product name happened to sit nearby: "Precisamos da Lista de Campos do
            # Protheus" was rejected only because "Protheus" forced the slow path, while the
            # identical "Falta o Relatorio de Vendas do Prado" went straight through here and
            # became a PERSON_NAME. Same shape, opposite outcome, for no reason.
            return _qualified_spans(runs, offset, text)
        return [span]

    return _qualified_spans(runs, offset, text)


def _qualified_spans(
    runs: list[list[re.Match[str]]], offset: int, text: str
) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    for run in runs:
        span = _qualify_run(run, offset, text)
        if span is not None:
            spans.append(span)
    return spans


def _has_a_person_head(run: list[re.Match[str]]) -> bool:
    """Whether the run OPENS with something that only a person opens with."""
    head = _fold(run[0].group(0))
    if head in _BR_TOP_NAMES or head in _PERSON_ONLY_HONORIFICS:
        return True
    # A job title is not a person on its own -- "Gerente de Contas" is a role with nobody in
    # it -- but it does not cancel the given name that follows it either. "Diretor Carlos da
    # Silva" is a person, and reading only token 0 would throw him away.
    if head in _NAME_HONORIFICS and len(run) > 1:
        return _fold(run[1].group(0)) in _BR_TOP_NAMES
    return False


def _is_a_genitive_chain(run: list[re.Match[str]]) -> bool:
    """A `<noun> da <noun> de <noun>` phrase with no given name opening it.

    This is the shape of ordinary business vocabulary -- "Lista de Campos", "Relatorio de
    Vendas", "Banco do Brasil" -- and roughly a third of the surname list doubles as an
    ordinary Portuguese noun (Campos, Cruz, Rocha, Ramos, Reis, Prado, Neves, Barros...), so
    such a phrase kept landing on the tail signal and coming out as a person. That mutilates
    the text the model reads AND files the hash of a phrase that is nobody.

    Brazilian full names use the very same connectives, but always AFTER a given name: the
    traditional "Jose da Silva" form goes with a traditional given name, which is exactly what
    `_BR_TOP_NAMES` covers. What falls in the gap is a person with an unlisted given name and a
    connective ("Wanderleia de Albuquerque"); that one leaks, and it is the deliberate price of
    not redacting ordinary vocabulary.
    """
    return not _has_a_person_head(run) and any(_fold(t.group(0)) in _NAME_CONNECTIVES for t in run)


def _ends_on_a_word_boundary(end: int, text: str) -> bool:
    """False when the cut falls in the middle of a word in the FULL text.

    Checked against `text` and not against the match: the cut lands exactly at the end of
    the match, so the character that tells whether it is mid-word is the one after it.
    """
    return end >= len(text) or not text[end].isalpha()


def _qualify_run(run: list[re.Match[str]], offset: int, text: str) -> tuple[int, int] | None:
    """Accepts a clean stretch as a name, or returns None."""
    # An orphan connective at the edge does not sustain a name: "Ana Souza de" -> "Ana Souza".
    while run and _fold(run[0].group(0)) in _NAME_CONNECTIVES:
        run = run[1:]
    while run and _fold(run[-1].group(0)) in _NAME_CONNECTIVES:
        run = run[:-1]
    if len(run) < 2:
        return None
    # A signal proper to a person, read from either end of the run.
    #
    # HEAD: a known BR given name, or an honorific. Job titles (Gerente, Diretor, Presidente) do
    # NOT count: alone they head phrases that are only a role, with nobody in them --
    # "Gerente de Contas Oracle" became a PERSON_NAME carrying a hash of "Gerente de Contas".
    #
    # TAIL: a common BR surname. Reading only the head left most real names on the floor, because
    # _BR_TOP_NAMES holds 300 given names and the country has far more -- "Edson Ribeiro Protheus"
    # went to the LLM in clear, which is the exact leak this trim exists to close. Brazilian full
    # names end in a surname, and that is a signal the head cannot give. It also keeps the
    # composite company name out: "Acme Software Solutions" trims to "Software Solutions", whose
    # last token is no surname, and "Acme Financeiro Pro" to "Financeiro Pro", likewise.
    if not _has_a_person_head(run):
        if _fold(run[-1].group(0)) not in _BR_TOP_SURNAMES:
            return None
        # Tail-only is the weak signal, and it is the one an ordinary business phrase trips.
        if _is_a_genitive_chain(run):
            return None

    start, end = offset + run[0].start(), offset + run[-1].end()
    # The cut cannot end in the middle of a word: a run ending at a letter `_WORD_RE` cannot
    # see would send the placeholder out glued to the tail -- "[[PERSON_NAME_1]]ñez".
    if not _ends_on_a_word_boundary(end, text):
        return None
    return start, end


def _tighten_to_tokens(start: int, end: int, text: str) -> tuple[int, int] | None:
    """Shrinks a span to its first and last name token, or None if nothing is left.

    `_claim_free_parts` cuts a candidate at the edges of what is already covered, and the
    leftover carries whatever fell in the gap: the separating space, a comma, an orphan
    connective. Claimed as-is, the span swallowed that space into the placeholder and filed
    `_hash(" Silva")` -- a hash of something nobody wrote, which defeats the point of the
    redaction record being auditable.
    """
    tokens = list(_WORD_RE.finditer(text[start:end]))
    while tokens and _fold(tokens[0].group(0)) in _NAME_CONNECTIVES:
        tokens = tokens[1:]
    while tokens and _fold(tokens[-1].group(0)) in _NAME_CONNECTIVES:
        tokens = tokens[:-1]
    if not tokens:
        return None
    lo, hi = start + tokens[0].start(), start + tokens[-1].end()
    # Both edges, for the same reason: a covered span that ended in mid-word leaves the tail
    # of that word as the fragment, and a tail that happens to be on the lists ("...costa")
    # would otherwise be claimed from inside another word.
    if lo > 0 and text[lo - 1].isalpha():
        return None
    return (lo, hi) if _ends_on_a_word_boundary(hi, text) else None


def _is_a_name_on_its_own(value: str) -> bool:
    """Whether the stretch still reads as a name once detached from its match.

    A leftover fragment no longer has the context that justified the original match, so it
    has to stand by itself: either the whole thing is one of the name shapes, or it is a
    single token that the lists recognize. Anything else -- a stray connective, a lone
    ordinary word -- is not claimed. Without this, `" de"` left over between two covered
    spans became a PERSON_NAME.
    """
    if _NAME_PREFIX_RE.fullmatch(value) or _NAME_SEQUENCE_RE.fullmatch(value):
        return True
    folded = _fold(value)
    return folded in _BR_TOP_NAMES or folded in _BR_TOP_SURNAMES


def _apply_basic_patterns(
    text: str,
) -> tuple[str, list[Redaction], dict[PiiType, int]]:
    """Applies the 5 deterministic patterns (email/CPF/CNPJ/card/phone).

    Returns `(intermediate_text, redactions, counters)`. The intermediate text
    already contains placeholders in place of each match -- it is over it that the
    PERSON_NAME step operates, ensuring substrings of basic PII do not become
    false names.
    """
    matches: list[_Match] = []
    for pii_type, pattern in _BASIC_PATTERNS:
        validator = _VALIDATORS.get(id(pattern))
        for m in pattern.finditer(text):
            raw = m.group(0)
            # Raw pattern (CPF/CNPJ without mask): requires a valid check digit to
            # avoid false positives on generic numeric codes (order ID,
            # tracking, NFE etc.).
            if validator is not None and not validator(raw):
                continue
            matches.append(_Match(type=pii_type, start=m.start(), end=m.end(), value=raw))

    matches.sort(key=lambda x: x.start)

    counters: dict[PiiType, int] = defaultdict(int)
    redactions: list[Redaction] = []
    rebuilt: list[str] = []
    cursor = 0

    for m in matches:
        if m.start < cursor:  # overlap between types -- ignores the second
            continue
        counters[m.type] += 1
        placeholder = f"[[{m.type.value}_{counters[m.type]}]]"
        rebuilt.append(text[cursor : m.start])
        rebuilt.append(placeholder)
        redactions.append(
            Redaction(placeholder=placeholder, type=m.type, originalHash=_hash(m.value))
        )
        cursor = m.end

    rebuilt.append(text[cursor:])
    return "".join(rebuilt), redactions, counters


def _redact_person_names(
    text: str,
    counters: dict[PiiType, int],
) -> tuple[str, list[Redaction]]:
    """Detects and redacts proper names over `text` (already partially redacted).

    Applies three heuristics in order, always skipping ranges already covered:

    1. Prefix + Title Case (`Dr. Carlos Silva`).
    2. 2-4 consecutive Title Case words (`Marina Alves`).
    3. Isolated BR first name against the hardcoded list (`Lucas`, `Marina`).

    The negative list filters products/companies/technologies before generating the
    placeholder. Each occurrence gets a new number (no dedup -- explicit
    scope decision).
    """
    person_matches: list[_Match] = []
    covered: list[tuple[int, int]] = []  # ranges (start, end) already consumed

    def _is_covered(start: int, end: int) -> bool:
        return any(not (end <= cs or start >= ce) for cs, ce in covered)

    def _claim(start: int, end: int, value: str) -> None:
        person_matches.append(_Match(type=PiiType.PERSON_NAME, start=start, end=end, value=value))
        covered.append((start, end))

    def _claim_free_parts(start: int, end: int) -> None:
        """Claims the candidate, trimming what is already covered instead of discarding it.

        `_is_covered` alone is all-or-nothing, and that started losing names when
        Pattern 1 began claiming TRIMMED spans: a Pattern 2 match that was entirely
        clean and merely TOUCHED that trim was thrown away whole, and the surname
        went out in the clear. Here whatever is free remains, and each free piece is
        validated again as a name before being claimed.
        """
        cursor = start
        for cs, ce in sorted(covered):
            if ce <= cursor or cs >= end:
                continue
            if cs > cursor:
                _qualify_and_claim(cursor, min(cs, end))
            cursor = max(cursor, ce)
            if cursor >= end:
                return
        if cursor < end:
            _qualify_and_claim(cursor, end)

    def _qualify_and_claim(start: int, end: int) -> None:
        for s, e in _spans_without_negatives(text[start:end], start, text):
            tightened = _tighten_to_tokens(s, e, text)
            if tightened is None:
                continue
            s, e = tightened
            if _is_covered(s, e):
                continue
            if not _is_a_name_on_its_own(text[s:e]):
                continue
            _claim(s, e, text[s:e])

    # Pattern 1: prefix (honorific / job title + name)
    for m in _NAME_PREFIX_RE.finditer(text):
        for start, end in _spans_without_negatives(m.group(0), m.start(), text):
            _claim_free_parts(start, end)

    # Pattern 2: Title Case sequence (2-4 words)
    for m in _NAME_SEQUENCE_RE.finditer(text):
        for start, end in _spans_without_negatives(m.group(0), m.start(), text):
            _claim_free_parts(start, end)

    # Pattern 3: isolated BR first name (Title Case, against the hardcoded list).
    # _NAME_TOKEN_RE (Title Case only) avoids false-positives on common
    # PT-BR nouns ("rosa", "clara", "vera" — all in _BR_TOP_NAMES as
    # female names but also generic lowercase words).
    for m in _NAME_TOKEN_RE.finditer(text):
        if _is_covered(m.start(), m.end()):
            continue
        token = m.group(0)
        if _fold(token) not in _BR_TOP_NAMES:
            continue
        if _is_negative(token):
            continue
        _claim(m.start(), m.end(), token)

    person_matches.sort(key=lambda x: x.start)

    redactions: list[Redaction] = []
    rebuilt: list[str] = []
    cursor = 0
    for m in person_matches:
        if m.start < cursor:
            continue
        counters[PiiType.PERSON_NAME] += 1
        placeholder = f"[[PERSON_NAME_{counters[PiiType.PERSON_NAME]}]]"
        rebuilt.append(text[cursor : m.start])
        rebuilt.append(placeholder)
        redactions.append(
            Redaction(
                placeholder=placeholder,
                type=PiiType.PERSON_NAME,
                originalHash=_hash(m.value),
            )
        )
        cursor = m.end

    rebuilt.append(text[cursor:])
    return "".join(rebuilt), redactions


def redact(text: str) -> PiiRedactionV1:
    """Replaces PII with placeholders in the `[[TYPE_N]]` format.

    Two-stage flow:
      1. Basic deterministic patterns (email/CPF/CNPJ/card/phone).
      2. PERSON_NAME heuristics over the already partially redacted text --
         ensures e.g. a name inside an e-mail will not be redacted again.
    """
    intermediate, basic_redactions, counters = _apply_basic_patterns(text)
    final_text, person_redactions = _redact_person_names(intermediate, counters)
    return PiiRedactionV1(
        redactedText=final_text,
        redactions=basic_redactions + person_redactions,
    )
