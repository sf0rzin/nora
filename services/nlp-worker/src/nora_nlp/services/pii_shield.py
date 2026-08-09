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


# Prefix (honorifics + job titles) followed by 1-5 Title Case words,
# supporting PT-BR connectives (`da`, `de`, `do`, `das`, `dos`, `e`).
# Captures "Dr. Carlos Silva", "Sra. Marina Alves", "Profa. Ana de Souza",
# "Sr. Jose da Silva Pereira", "Eng. Joao", "Diretor Carlos da Silva", etc.
_NAME_PREFIX_RE = re.compile(
    r"\b(?:Sr|Sra|Srta|Dr|Dra|Prof|Profa|Eng|Engenheiro|Engenheira|"
    r"Cap|Sgt|Gen|Pe|Padre|Diretor|Diretora|Coordenador|Coordenadora|"
    r"Gerente|Pres|Presidente)\.?\s+"
    r"[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+(?:d[aeo]s?|e)\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"|\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){0,4}"
)

# 2-5 consecutive Title Case words, with support for PT-BR connectives
# (`da`, `de`, `do`, `das`, `dos`, `e`) between tokens. "Jose da Silva Pereira"
# matches as a single compound name instead of two separate ones. All-caps
# tokens ("TOTVS", "RM") are ignored for not having lowercase at the end --
# avoids capturing acronyms as part of a compound name.
_NAME_SEQUENCE_RE = re.compile(
    r"\b[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+(?:d[aeo]s?|e)\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"|\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){1,4}\b"
)

# Generic alphabetic token (used by `_tokenize` for the negative list — it can
# be case-insensitive because we only check against the negative list, which also
# uses casefold).
_WORD_RE = re.compile(r"\b[A-Za-zÁÉÍÓÚÂÊÔÀÃÕÇáéíóúâêôàãõç]+\b")

# Isolated BR first name: ONLY matches Title Case (`Joao`, `Marina`). Matching
# lowercase (`joao`, `rosa`, `clara`) produces massive false-positives on
# common PT-BR nouns. In real corporate transcripts, first
# names always appear Title Case (automatic capitalization by the dictation) or
# all-caps in formal context (which are filtered by `_PERSON_NAME_NEGATIVE_LIST`).
_NAME_TOKEN_RE = re.compile(r"\b[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+\b")


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
        # No negative token at all: the match passes whole, as it always did.
        return [(offset, offset + len(value))] if runs else []

    spans: list[tuple[int, int]] = []
    for run in runs:
        span = _qualify_run(run, offset, text)
        if span is not None:
            spans.append(span)
    return spans


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
    head = _fold(run[0].group(0))
    tail = _fold(run[-1].group(0))
    if (
        head not in _BR_TOP_NAMES
        and head not in _PERSON_ONLY_HONORIFICS
        and tail not in _BR_TOP_SURNAMES
    ):
        return None

    start, end = offset + run[0].start(), offset + run[-1].end()
    # The cut cannot end in the middle of a word. _WORD_RE only knows the letters of the declared
    # class, so a surname with a letter outside it ("Núñez") makes the run end halfway -- and the
    # placeholder went out glued to the tail: "[[PERSON_NAME_1]]ñez". The check is against the
    # FULL TEXT, not against the match: the cut falls exactly at the end of the match.
    if end < len(text) and text[end].isalpha():
        return None
    return start, end


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
            if _is_covered(s, e):
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
