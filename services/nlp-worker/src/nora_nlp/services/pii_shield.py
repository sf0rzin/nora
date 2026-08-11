"""PII Shield baseline: deterministic regex applied before any call to the LLM.

Covers the basic Brazilian types (e-mail, phone, CPF, CNPJ, card) and detects
proper names via heuristic regex + hardcoded list of top BR names + negative list
of technical/product/company terms. ADDRESS is left for a future slice.

The PERSON_NAME strategy is detailed in the docstring of `_redact_person_names`.
"""

from __future__ import annotations

import bisect
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
#
# The local part is bounded to 64, which is the RFC 5321 maximum and is also what stops the
# backtracking. `-` is not a `\w`, so the left anchor succeeds after every hyphen: on
# hyphen-dense text the unbounded `+` scanned to the end of the string from every one of them
# and `redact` went quadratic -- 154KB of "Ana-B-" spent 4.8s inside this one pattern, on the
# request thread, for a transcript cap of 1_000_000 chars. The `'@' in text` pre-filter the
# comment above has always promised is applied in `_apply_basic_patterns` now; the bound is
# what covers the case where the text does contain an address.
_EMAIL_RE = re.compile(r"(?<![\w@])[a-zA-Z0-9._%+\-]{1,64}@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b")

# BR phones: DDD mandatory (8 or 9 digits after the DDD). Conservative
# on purpose: a phone without DDD (98765-4321) is too small to tell apart
# from numeric codes/protocols without massive false-positives.
#
# Tolerances (audit 2026-06-16, ADR 0012) -- all WITHOUT relaxing the
# DDD requirement (a phone has no check digit, so a mandatory DDD holds the FP):
#   - `(?:\+?55[\s.\-]?)?`        optional international +55 prefix.
#   - `(?:\(\s*0?\d{2}\s*\)|0?\d{2})`
#                                 parentheses with inner space ("( 11 )") and DDD with the
#                                 old 3-digit zero ("(011)"). The two forms are ALTERNATIVES:
#                                 written as `\(?\s*0?\d{2}\s*\)?` the inner `\s*` was not
#                                 gated on the parenthesis, so an unparenthesised number ate
#                                 the space in front of it -- "telefone e 11 98765-4321"
#                                 became "telefone e[[PHONE_1]]", destroying the word
#                                 separator in the text the model reads, and filing
#                                 `_hash(" 11 98765-4321")`, an edge-space value nobody wrote.
#                                 It also matched a half-open "(11 98765-4321)", swallowing the
#                                 opening paren and leaving the closing one dangling.
#   - `(?:9[\s.\-/]?)?`           mobile 9th digit dictated LOOSE between the DDD
#                                 and the number ("(11) 9 8765-4321") -- common in
#                                 speech-to-text transcription.
#   - `[\s.\-/]`                  separator `/` ("11/98765/4321") besides space/
#                                 dot/hyphen.
# DEFERRED (high FP risk without a check digit -- left for a future slice):
#   - phone WITHOUT DDD ("99988-7766", "3003-1234"): too small to tell apart
#     from numeric codes/protocols.
#   - non-BR international ("+1 415 555 2671"): generalizing `\+\d{1,3}` explodes FP.
#   - the DDD/number separator is `[\s.\-/]{0,3}` and NOT `*`. Unbounded, a DDD followed by any
#     run of separators became a phone and "Sala 11 - 98765 4321 reservada" lost the room number
#     and the range to a placeholder. Three is what the real forms need -- "11  98765-4321",
#     "11 - 98765-4321", and the "/" separator this file's own docstring lists as supported,
#     "11 / 98765-4321". A tighter bound of 2 dropped the last two, which is a leak.
#
#     Three also still admits the contrived "Sala 11 - 98765 4321": that shape is identical to
#     a real number and nothing in the text distinguishes them. Redacting a room number costs
#     one phrase of context; missing a phone sends it to the model provider. Deliberate.
#   - the bare-DDD branch keeps its optional trailing `)`, deliberately. "Ver o item (ramal 11)
#     98765-4321" is a real number and dropping the branch left it in the clear; the cost is
#     that the match carries a parenthesis belonging to the sentence and the hash differs from
#     the same number typed "(11) 98765-4321". A leaked phone outweighs an odd hash.
_PHONE_RE = re.compile(
    r"(?<!\d)(?:\+?55[\s.\-]?)?(?:\(\s*0?\d{2}\s*\)|0?\d{2}\)?)"
    r"[\s.\-/]{0,3}(?:9[\s.\-/]?)?\d{4,5}[\s.\-/]?\d{4}(?!\d)"
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

# A hyphenated compound is ONE token. Without the `(?:-...)*` the match stopped at the hyphen
# -- `\b` holds between a letter and `-` -- and `_ends_on_a_word_boundary` waved it through
# because `-` is not `.isalpha()`, so "Ana Paula Silva-Costa" came out as
# "[[PERSON_NAME_1]]-Costa": a corrupted token for the model and the second half of the
# surname in the clear.
_TITLE_WORD = f"[{_UPPER}][{_LOWER}]+(?:-[{_UPPER}][{_LOWER}]+)*"

# An ALL-CAPS token, for the speaker labels and attendee lists that meeting minutes and
# diarised transcripts are full of ("CARLOS SILVA: fechamos o escopo").
_CAPS_WORD = f"[{_UPPER}]{{2,}}(?:-[{_UPPER}]{{2,}})*"

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

# 2-8 consecutive Title Case words, with support for PT-BR connectives
# (`da`, `de`, `do`, `das`, `dos`, `e`) between tokens. "Jose da Silva Pereira"
# matches as a single compound name instead of two separate ones. All-caps
# tokens ("TOTVS", "RM") are ignored for not having lowercase at the end --
# avoids capturing acronyms as part of a compound name.
#
# The cap was 5 tokens. A full Brazilian name reaches that on its own -- "Maria de Fatima dos
# Santos Silva" is six -- and product names padding the middle reach it easily: in
# "Dr. Carlos Protheus Silva Protheus Marina Alves" the match stopped at "Marina", so "Alves"
# fell outside every pattern (Pattern 3 only knows given names) and went to the LLM in the
# clear. Widening is safe because the match is not the decision: `_spans_without_negatives`
# splits it on negative terms and on `e`, and each run is gated on its own.
_NAME_SEQUENCE_RE = re.compile(
    f"\\b{_TITLE_WORD}(?:\\s+(?:d[aeo]s?|e)\\s+{_TITLE_WORD}|\\s+{_TITLE_WORD}){{1,7}}\\b"
)

# Generic alphabetic token. Splits a candidate into tokens so each can be folded and looked
# up on its own; it can be case-insensitive because every comparison downstream goes through
# `_fold`, which casefolds as well as stripping accents.
_WORD_RE = re.compile(f"[{_UPPER}{_LOWER}]+")

# Isolated BR first name: ONLY matches Title Case (`Joao`, `Marina`). Matching
# lowercase (`joao`, `rosa`, `clara`) produces massive false-positives on
# common PT-BR nouns.
#
# This used to say all-caps names were "filtered by `_PERSON_NAME_NEGATIVE_LIST`", which was
# not true in any direction: that list holds products and acronyms, and its only effect
# anywhere in the module is to SUPPRESS a redaction -- it can never cause one. All-caps full
# names simply matched no pattern and went to the LLM whole. Pattern 4 covers them now.
_NAME_TOKEN_RE = re.compile(f"\\b{_TITLE_WORD}\\b")

# 2-5 consecutive ALL-CAPS words. Unlike the Title Case sequence this one is NOT trusted on
# its own -- an all-caps pair is far more often an acronym string than a name -- so Pattern 4
# only claims it when one end is on the name lists. See the pattern for why the docstring that
# used to sit here, claiming `_PERSON_NAME_NEGATIVE_LIST` handled these, was wrong.
_CAPS_SEQUENCE_RE = re.compile(f"\\b{_CAPS_WORD}(?:\\s+{_CAPS_WORD}){{1,4}}\\b")

# A single ALL-CAPS token used as a speaker label, "MARINA: fechamos o escopo". The sequence
# above needs two tokens, so a one-word label matched nothing at all. The colon is the signal;
# the token still has to be a listed given name, or every "ATA:" and "OBS:" becomes a person.
_CAPS_LABEL_RE = re.compile(f"\\b{_CAPS_WORD}(?=\\s*:)")

# A speaker label opening a line: up to three ALL-CAPS words followed by a colon. This is the
# one shape where neither end needs to be on a list, and it is what closed the last of the
# measured leak -- "NIVALDO ZANCHETTA: fechamos o escopo" has a given name on no list and a
# surname on no list, so nothing else can see it. Diarisers and minute-takers produce exactly
# this, and an ordinary phrase almost never opens a line and ends in a colon; the ones that do
# ("ATA:", "PAUTA:", "OBS:") are ordinary vocabulary and are filtered as such.
_CAPS_SPEAKER_RE = re.compile(f"(?m)^[ \\t]*({_CAPS_WORD}(?:[ \\t]+{_CAPS_WORD}){{0,2}})[ \\t]*:")

# Third-person preterite and gerund endings. What follows a name in minutes is a verb --
# "MARINA APROVOU", "CARLOS ASSUMIU" -- and no Brazilian surname ends this way, so this is
# what lets a name be taken as given-name-plus-one-token without swallowing the sentence.
_VERB_TAIL_RE = re.compile(r"(?:ou|iu|eu|aram|eram|iram|ando|endo|indo|aria|eria|iria|sse)$")


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


_NAME_CONNECTIVES: frozenset[str] = frozenset(
    _fold(c) for c in ("da", "de", "do", "das", "dos", "e")
)

# The genitive prepositions ONLY. `e` is deliberately absent: it is a coordinating
# conjunction, and in this corpus its job is to join two DIFFERENT people ("Osvaldo Pinheiro
# e Marina Alves"). Counting it as a connective in `_is_a_genitive_chain` made that phrase
# read as one noun phrase and refused it whole, leaking both names -- and the outcome flipped
# on word order, since a listed given name at the head still qualified. The two sets are
# separated so the edge-trimming can keep dropping an orphan `e` without the genitive test
# ever seeing it.
_GENITIVE_PREPOSITIONS: frozenset[str] = frozenset(
    _fold(c) for c in ("da", "de", "do", "das", "dos")
)

# The coordinating conjunction. It SEPARATES two people ("Osvaldo Pinheiro e Marina Alves"),
# so a run is split on it and each side qualified on its own — which is also what keeps
# "Pesquisa e Desenvolvimento" from being read as one person.
_CONJUNCTION: str = _fold("e")

# Ordinary pt-BR words that head a noun phrase and are never a given name.
#
# This list decides the DEFAULT for a genitive chain, and the default matters more than the
# list does. The gate used to read "unknown head + a preposition => not a person", and the
# most common Brazilian full-name shape is exactly that: "Nivaldo da Silva", "Zoraide dos
# Santos", "Marlene da Costa". Twelve of sixteen realistic names went to the LLM in the clear
# — the very particle that MARKS a full name switched the shield off. The tail signal was
# there (Silva, Santos, Costa are all on the surname list) and was being discarded.
#
# So the default is inverted: an unknown head with a surname tail is a PERSON, and only a head
# on this list makes the phrase ordinary vocabulary. For a PII shield the unknown case has to
# fail towards redacting. The cost is over-redacting a phrase whose head is missing here, which
# degrades one analysis; the cost the other way is a name reaching the model provider.
_COMMON_PHRASE_HEADS: frozenset[str] = frozenset(
    _fold(w)
    for w in (
        # documents and artefacts
        "Lista",
        "Listas",
        "Relatorio",
        "Relatorios",
        "Ordem",
        "Ordens",
        "Nota",
        "Notas",
        "Fatura",
        "Faturas",
        "Contrato",
        "Contratos",
        "Proposta",
        "Propostas",
        "Pedido",
        "Pedidos",
        "Planilha",
        "Planilhas",
        "Tabela",
        "Tabelas",
        "Ata",
        "Atas",
        "Pauta",
        "Resumo",
        "Manual",
        "Guia",
        "Politica",
        "Politicas",
        "Diretriz",
        "Diretrizes",
        "Cadastro",
        "Cadastros",
        "Painel",
        "Paineis",
        "Tela",
        "Telas",
        "Campo",
        "Campos",
        "Item",
        "Itens",
        "Anexo",
        "Anexos",
        "Documento",
        "Documentos",
        "Chamado",
        "Chamados",
        # organisation
        "Time",
        "Times",
        "Setor",
        "Setores",
        "Area",
        "Areas",
        "Grupo",
        "Grupos",
        "Filial",
        "Filiais",
        "Equipe",
        "Equipes",
        "Diretoria",
        "Conselho",
        "Comite",
        "Departamento",
        "Unidade",
        "Unidades",
        "Matriz",
        "Banco",
        "Bancos",
        "Cliente",
        "Clientes",
        "Fornecedor",
        "Fornecedores",
        "Empresa",
        "Empresas",
        # work
        "Plano",
        "Planos",
        "Projeto",
        "Projetos",
        "Processo",
        "Processos",
        "Fluxo",
        "Fluxos",
        "Etapa",
        "Etapas",
        "Fase",
        "Fases",
        "Meta",
        "Metas",
        "Prazo",
        "Prazos",
        "Entrega",
        "Entregas",
        "Escopo",
        "Escopos",
        "Ajuste",
        "Ajustes",
        "Revisao",
        "Revisoes",
        "Validacao",
        "Acompanhamento",
        "Contagem",
        "Cronograma",
        "Reuniao",
        "Reunioes",
        "Agenda",
        "Modulo",
        "Modulos",
        "Versao",
        "Versoes",
        "Ambiente",
        "Ambientes",
        "Integracao",
        "Integracoes",
        "Migracao",
        "Implantacao",
        "Homologacao",
        "Teste",
        "Testes",
        "Correcao",
        "Correcoes",
        "Melhoria",
        "Melhorias",
        "Pipeline",
        "Roadmap",
        # money and numbers
        "Custo",
        "Custos",
        "Despesa",
        "Despesas",
        "Receita",
        "Receitas",
        "Orcamento",
        "Faturamento",
        "Financeiro",
        "Fiscal",
        "Contabil",
        "Folha",
        "Estoque",
        "Compra",
        "Compras",
        "Venda",
        "Vendas",
        "Suprimento",
        "Suprimentos",
        "Conta",
        "Contas",
        "Indicador",
        "Indicadores",
        "Volume",
        "Total",
        "Saldo",
        "Margem",
        "Preco",
        "Precos",
        # governance
        "Governanca",
        "Risco",
        "Riscos",
        "Qualidade",
        "Auditoria",
        "Seguranca",
        "Saude",
        "Compliance",
        "Juridico",
        "Suporte",
        "Atendimento",
        "Pesquisa",
        "Desenvolvimento",
        "Marketing",
        "Comercial",
        "Operacoes",
        "Logistica",
        "Producao",
        "Manutencao",
        # verbs that open a sentence in minutes
        "Preciso",
        "Precisa",
        "Precisamos",
        "Precisam",
        "Falta",
        "Faltam",
        "Faltou",
        "Temos",
        "Tenho",
        "Tem",
        "Vamos",
        "Vou",
        "Vai",
        "Ficou",
        "Ficamos",
        "Ficaram",
        "Mandei",
        "Mandamos",
        "Abrimos",
        "Abri",
        "Rodamos",
        "Rodei",
        "Revisar",
        "Revisamos",
        "Configurar",
        "Ajustar",
        "Fizemos",
        "Fiz",
        "Segue",
        "Seguem",
        "Ficha",
        "Novos",
        "Novo",
        "Nova",
        "Novas",
        "Proximo",
        "Proxima",
        "Proximos",
        "Proximas",
        "Outro",
        "Outra",
        "Outros",
        "Outras",
        "Primeiro",
        "Primeira",
        "Ultimo",
        "Ultima",
        # function words. They reach here Title Cased at the start of a sentence, or
        # upper-cased inside an all-caps line, and they must never open a name:
        # "FICOU COM EDSON SILVA" has to yield EDSON SILVA, not COM EDSON SILVA.
        "Com",
        "Para",
        "Por",
        "Sem",
        "Sob",
        "Sobre",
        "Entre",
        "Ate",
        "Apos",
        "Desde",
        "Contra",
        "Conforme",
        "Como",
        "Que",
        "Quando",
        "Onde",
        "Mas",
        "Nao",
        "Sim",
        "Ja",
        "Tambem",
        "Ainda",
        "Sempre",
        "Nunca",
        "Hoje",
        "Ontem",
        "Amanha",
        "Agora",
        "Aqui",
        "Dia",
        "Dias",
        "Mes",
        "Meses",
        "Ano",
        "Anos",
        "Hora",
        "Horas",
        "Semana",
        "Semanas",
        "Presentes",
        "Presente",
        "Ausente",
        "Ausentes",
        "Decisao",
        "Decisoes",
        "Contato",
        "Contatos",
        "Responsavel",
        "Responsaveis",
        "Solicitante",
        "Autor",
        "Relator",
        "Aprovador",
        "Participante",
        "Participantes",
        "Convidado",
        "Convidados",
        # Ordinary nouns that head an all-caps heading or a spreadsheet label. Without them
        # "LOJA CAMPOS", "REGIONAL CAMPOS", "GALPAO PRADO", "OBRA CRUZ" were filed as people,
        # a third of the surname list being ordinary nouns and place names.
        "Loja",
        "Lojas",
        "Regional",
        "Municipio",
        "Produto",
        "Produtos",
        "Linha",
        "Linhas",
        "Categoria",
        "Categorias",
        "Terminal",
        "Condominio",
        "Galpao",
        "Obra",
        "Obras",
        "Gestao",
        "Seguro",
        "Seguros",
        "Bloco",
        "Sala",
        "Andar",
        "Deposito",
        "Almoxarifado",
        "Frota",
        "Veiculo",
        "Turno",
        "Escala",
        "Lote",
        "Lotes",
        "Filial",
        "Regiao",
        "Zona",
        "Distrito",
        "Recursos",
        "Gerencia",
        "Controladoria",
        "Inteligencia",
        "Engenharia",
        "Boleto",
        "Demonstrativo",
        "Comprovante",
        "Livro",
        "Salario",
        "Certificado",
        "Acordo",
        "Assinatura",
        "Copia",
        "Prova",
        "Sistema",
        "Sistemas",
        "Base",
        "Bolsa",
        "Tribunal",
        "Assembleia",
        "Ministerio",
        "Associacao",
        "Federacao",
        "Delegacia",
        "Secretaria",
        "Prefeitura",
        "Caixa",
        "Imposto",
        "Impostos",
        "Lei",
        "Leis",
        "Carta",
        "Cartas",
        "Boa",
        "Bom",
        "Boas",
        "Bons",
        "Tarde",
        "Manha",
        "Noite",
        # Words that open a line and end in a colon without naming anybody.
        "Obs",
        "Observacao",
        "Observacoes",
        "Aviso",
        "Avisos",
        "Local",
        "Data",
        "Titulo",
        "Objetivo",
        "Objetivos",
        "Status",
        "Geral",
        "Inicio",
        "Fim",
        "Duracao",
        "Participacao",
        "Encaminhamentos",
        "Encaminhamento",
        "Conclusao",
        "Conclusoes",
        # The section heads a minute-taker actually writes. Pattern 6 claims 1-3 all-caps words
        # at the head of a line with a colon and needs neither end on a list, so an unguarded
        # head is a person: "CONTEXTO: definimos o escopo" lost its heading. That is not one
        # more over-redaction -- the analysis prompt asks the model to build its output sections
        # from the transcript's own structure, so deleting the headings deletes the scaffolding
        # the headline output is built on.
        "Contexto",
        "Contextos",
        "Problema",
        "Problemas",
        "Solucao",
        "Solucoes",
        "Topico",
        "Topicos",
        "Resultado",
        "Resultados",
        "Um",
        "Uma",
        "Uns",
        "Umas",
        "Este",
        "Esta",
        "Estes",
        "Estas",
        "Esse",
        "Essa",
        "Esses",
        "Essas",
        "Aquele",
        "Aquela",
        "Nosso",
        "Nossa",
        "Nossos",
        "Nossas",
        "Seu",
        "Sua",
        "Seus",
        "Suas",
        "Assunto",
        "Assuntos",
        "Tema",
        "Temas",
        "Ponto",
        "Pontos",
        "Acao",
        "Acoes",
        "Tarefa",
        "Tarefas",
        # place-name heads: an address is not a person, and a good third of the surname
        # list doubles as a place ("Bairro SANTA CRUZ", "Vila Prado").
        "Santa",
        "Santo",
        "Sao",
        "Vila",
        "Bairro",
        "Rua",
        "Avenida",
        "Praca",
        "Centro",
        "Jardim",
        "Parque",
        # The words that open a Brazilian place name, and the adjectives that close an
        # institution's. Not a gazetteer -- just the heads, which is what the rule reads.
        "Belo",
        "Porto",
        "Rio",
        "Praia",
        "Serra",
        "Monte",
        "Lago",
        "Ponta",
        "Barra",
        "Cabo",
        "Foz",
        "Governador",
        "Economica",
        "Economico",
        "Federal",
        "Estadual",
        "Municipal",
        "Interno",
        "Interna",
        "Externo",
        "Externa",
        "Bruto",
        "Liquido",
        "Informacao",
        "Informacoes",
        "Tecnologia",
        "Comunicacao",
        "Administracao",
        "Financas",
        "Patrimonio",
        "Estrada",
        "Rodovia",
        "Alameda",
        "Travessa",
        "Cidade",
        "Estado",
        # quantifiers and determiners
        "Muito",
        "Muita",
        "Muitos",
        "Muitas",
        "Pouco",
        "Pouca",
        "Poucos",
        "Poucas",
        "Todo",
        "Toda",
        "Todos",
        "Todas",
        "Algum",
        "Alguma",
        "Alguns",
        "Algumas",
        "Varios",
        "Varias",
        "Ambos",
        "Ambas",
        "Cada",
        "Qualquer",
        "Nenhum",
        "Nenhuma",
        "Mais",
        "Menos",
        "Tantos",
        "Tantas",
        "Ambas",
        "Demais",
    )
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

# Subset that only appears before a PERSON. Job titles are left out on purpose:
# "Gerente de Contas", "Diretor Comercial" and "Presidente do Conselho" are roles that exist
# with nobody in the middle, and accepting them as a person signal in the trimming path turned
# a job-title phrase into a PERSON_NAME -- with the title's hash in the redaction record.
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


def _split_on_allow_list(
    tokens: list[re.Match[str]],
    separators: frozenset[str] = frozenset(),
) -> list[list[re.Match[str]]]:
    """Contiguous stretches of `tokens` with the allow-listed terms taken out.

    ARBITRATION. This is the one place where an allow-listed term meets a name candidate, and
    the rule is a sentence: **the term covers its own surface form and nothing else.** It is
    removed from the candidate, it does not widen over a neighbour, and it does not lower the
    standing of what is left -- each surviving stretch is judged exactly as it would be judged
    standing alone. Where that judgement is ambiguous the stretch is REDACTED, because an
    over-redaction costs a worse summary and an under-redaction sends a person's name to a
    third-party provider, which ADR 0012 forbids.

    Both halves of that rule used to be broken, and finding 5a is the pair of them:

    - `_caps_name_span` returned None for the WHOLE match when any token was allow-listed, so
      "SAP CARLOS SILVA" emitted a name that "CARLOS SILVA" redacts correctly. Three characters
      of allow list covering fourteen characters of person.
    - This function computed `has_negative` over the entire candidate and demoted *every*
      stretch in it onto a path that demands a listed given name or a listed surname, so
      "Protheus Wanderleia Kranz" emitted a name that "Wanderleia Kranz" redacts correctly. The
      lists hold 271 given names and 101 surnames; most real names are on neither, which is the
      whole reason the trusted path exists.

    Note what the allow list can and cannot do, because it is easy to read it the other way: its
    only possible effect anywhere in this module is to SUPPRESS a redaction. It can never cause
    one. So an allow-listed term reaching past its own characters can only ever produce a leak.

    `separators` are tokens that split a run without being allow-listed. The coordinating `e`
    is the only one: it joins two DIFFERENT people, so each side is a candidate of its own.
    Keeping the run whole made "Pesquisa e Desenvolvimento" one PERSON_NAME and made "Osvaldo
    Pinheiro e Marina Alves" stand or fall together.
    """
    runs: list[list[re.Match[str]]] = []
    current: list[re.Match[str]] = []
    for tok in tokens:
        folded = _fold(tok.group(0))
        if folded in _PERSON_NAME_NEGATIVE_LIST or folded in separators:
            if current:
                runs.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        runs.append(current)
    return runs


# Words that close a trading name. Kept separate from `_COMMON_PHRASE_HEADS`, which decides what
# may OPEN a phrase: this list decides what may close one, and the two questions have different
# answers -- "Sistemas" heads "Sistemas de Gestao" and also closes "Acme Sistemas".
#
# Every entry has to be a word no Brazilian carries as a surname, because refusing a span here is
# a refusal to redact. Checked against `_BR_TOP_SURNAMES` and `_BR_TOP_NAMES`: none of them
# appears on either.
_COMPANY_TAIL_WORDS: frozenset[str] = frozenset(
    _fold(w)
    for w in (
        "Software",
        "Solutions",
        "Solucoes",
        "Sistemas",
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
    )
)
# Deliberately absent: "SA", "ME", "EPP". This list is consulted from `_trusted_span`, which
# only ever sees Title Case runs, and `_TITLE_WORD` requires a lowercase letter after the first
# -- so those three could never match here. An entry that cannot fire is a control that reads as
# protection and is not, which is the defect class this module has been chasing for six rounds.


def _spans_without_negatives(value: str, offset: int, text: str) -> list[tuple[int, int]]:
    """All stretches of the candidate that still hold as a name, left to right.

    Returns a list because a negative token in the MIDDLE separates two distinct names: in
    "Ana Souza Protheus Carlos Silva" two clean stretches remain, and the previous version --
    which kept only the longest via `max()`, the first one on a tie -- discarded
    "Carlos Silva" entirely. The surname went out in the clear.
    """
    runs = _split_on_allow_list(
        list(_WORD_RE.finditer(value)),
        separators=frozenset({_CONJUNCTION}),
    )
    spans: list[tuple[int, int]] = []
    for run in runs:
        # Each stretch takes the same two chances an unsplit candidate takes, in the same order.
        # Skipping the first of them for a stretch that merely sat NEXT TO an allow-listed term
        # is what finding 5a was.
        span = _trusted_span(run, offset, text)
        if span is None:
            span = _qualify_run(run, offset, text)
        if span is not None:
            spans.append(span)
    return spans


def _name_bearing(run: list[re.Match[str]]) -> list[re.Match[str]]:
    """The tokens of a run that could carry a name.

    Connectives and ordinary vocabulary are stripped later by `_trusted_span` and
    `_tighten_to_tokens`, so counting raw tokens overestimates what a run has left. The
    corporate-suffix subtraction has to know how much would ACTUALLY survive: "do Kranz
    Solutions" is three tokens and one name, and a raw length test let it collapse to nothing.
    """
    return [
        t
        for t in run
        if _fold(t.group(0)) not in _NAME_CONNECTIVES
        and _fold(t.group(0)) not in _COMMON_PHRASE_HEADS
    ]


def _trusted_span(run: list[re.Match[str]], offset: int, text: str) -> tuple[int, int] | None:
    """The fast path: taken whole, without demanding a listed given name or surname.

    Demanding one here would drop every real name outside the two lists ("Kleber Zanchetta"),
    which is most of them. Two things are still checked. A genitive chain of ordinary
    vocabulary is refused and sent to `_qualify_run` -- leaving that out made the decision
    depend on whether a product name happened to sit nearby, so "Precisamos da Lista de Campos
    do Protheus" was rejected while the identical "Falta o Relatorio de Vendas do Prado" became
    a PERSON_NAME. And the span is tightened and boundary-checked, because a cut that lands
    mid-word emits a fragment: "Eng. Schürmann" once matched as "Eng. Sch".
    """
    if not run or _is_a_genitive_chain(run):
        return None
    # A corporate suffix CLOSES a company, never a person -- "Northwind Software Solutions
    # renovou o contrato" was coming back as a bare placeholder with the customer gone from the
    # summary. It is SUBTRACTED from the run, never used to refuse it, which is the same rule
    # `_split_on_allow_list` applies to the negative list and for the same reason.
    #
    # The first version of this refused the whole run, and reintroduced finding 5a pointing the
    # other way. Refusing sent the run to `_qualify_run`, which reads the surname signal off the
    # TAIL -- the slot the corporate word now occupied -- so the fallback could not rescue it
    # either. Measured against `main`: "Wanderleia Kranz\nDiretora de Tecnologia" redacted
    # before and leaked after, and so did "Kleber Silva Solutions", whose surname is on
    # `_BR_TOP_SURNAMES`. The attendee block of a set of minutes is exactly that shape and it
    # leaked 500 of 500 generated cases. An allow list that reaches past its own characters can
    # only ever produce a leak, whichever list it is.
    # `> 2`, NOT `> 1`, and the difference is a leak of a thousand generated cases.
    #
    # Subtracting down to a single token destroys the run instead of narrowing it:
    # `_is_a_name_on_its_own`, downstream in `_qualify_and_claim`, refuses a lone token that is
    # on neither name list, so NOTHING is claimed -- not the corporate word and not the person.
    # `main` claimed the pair whole. Measured, `main` against the `> 1` version:
    #
    #     "Wanderleia Solutions fechou o acordo."      main redacts, `> 1` LEAKS
    #     "Zanchetta Tecnologia apresentou a proposta."  main redacts, `> 1` LEAKS
    #
    # 600 of 600 off-list given names and 400 of 400 off-list surnames, and it reached through
    # the genitive route too ("O Protheus do Kranz Solutions travou"). That is finding 5a again,
    # in a third costume: a list reaching past its own characters, this time via the backstop
    # rather than via the qualification path. The lists hold 271 given names and 101 surnames,
    # so "most real names" is exactly the half that leaked.
    #
    # Stopping at two tokens means an ambiguous pair is never taken apart, so it is judged as
    # `main` judged it -- redacted, with the corporate word swallowed. That is the fail-closed
    # side of a case with no lexical signal: "Wanderleia Tecnologia" is a person and a
    # department, "Acme Solutions" is a company, and nothing in the text tells them apart.
    #
    # The price is real and is paid on the false-redaction rate: a three-token trading name
    # ending in a corporate word keeps only that word ("Northwind Software Solutions" ->
    # "[[PERSON_NAME_1]] Solutions"). Still better than `main`, which swallowed all three.
    # A run of NOTHING BUT corporate words is a trading name with no person in it. This is what
    # keeps "Acme Software Solutions" whole once the negative list has taken `Acme` out.
    if all(_fold(t.group(0)) in _COMPANY_TAIL_WORDS for t in run):
        return None
    # Counted on NAME-BEARING tokens, not on `len(run)`: the connectives and the leading
    # ordinary vocabulary are stripped further down, so "do Kranz Solutions" and
    # "Contato Kranz Solutions" are three tokens that become one, and a plain length test let
    # both leak.
    while _fold(run[-1].group(0)) in _COMPANY_TAIL_WORDS and len(_name_bearing(run[:-1])) >= 2:
        run = run[:-1]
    # A job title opening a run makes it a ROLE only when the run holds NOBODY -- every token a
    # title, a connective or ordinary vocabulary. "Gerente de Contas" and "Diretor Comercial"
    # are roles; "Coordenador Edson Silva" and "Gerente Wanderleia Prazo" are people with a
    # title in front. Reading only the last word refused those too, and a differential fuzz over
    # 40,000 strings found five regressions against `main`, all of them that.
    #
    # The rule is needed because `main` redacts "Gerente de Contas" as a person.
    # `test_a_job_title_alone_is_not_a_person` looks like it covers that and does not: every
    # string in it carries a term from the negative list ("Oracle", "Senior"), and what made the
    # assertion pass was finding 5a demoting the whole candidate. Drop the product and the job
    # title is redacted. The property the test names was never true.
    #
    # The `all(...)` is the whole rule. Two conditions that read as protection were dropped
    # from this branch after review measured them:
    #
    #   `head in _NAME_HONORIFICS`      subsumed. A run whose every token is a title, a
    #                                   connective or ordinary vocabulary already has such a
    #                                   head, or is caught by the `_COMMON_PHRASE_HEADS` branch
    #                                   below. Deleting it changed 0 of 13,500 strings.
    #   `head not in _PERSON_ONLY_HONORIFICS`
    #                                   worse than dead. It was written to mean "Sr." and "Dr."
    #                                   mark a person whatever follows -- but this branch only
    #                                   ever REFUSES, so excluding those heads can only make the
    #                                   shield redact MORE, never less. Its one measurable
    #                                   effect on 13,500 strings was to leave "Sr. Grupo
    #                                   confirmou." unredacted. It protected nothing and cost a
    #                                   redaction.
    if all(
        _fold(t.group(0)) in _NAME_HONORIFICS
        or _fold(t.group(0)) in _NAME_CONNECTIVES
        or _fold(t.group(0)) in _COMMON_PHRASE_HEADS
        for t in run
    ):
        return None
    # Ordinary vocabulary does not open a name either. "Contas Medicas" and "Nota Fiscal
    # Eletronica" were being claimed as people, with the hash of the phrase filed, purely
    # because the regex shape fits.
    if _fold(run[0].group(0)) in _COMMON_PHRASE_HEADS:
        # Drop the ordinary words in front and judge what is LEFT. "Contato Carlos Silva"
        # swallowed "Contato" into the placeholder and into the hash; "Contas Medicas" has
        # nothing behind the label at all.
        #
        # This was written the other way round -- refuse unless the last token is a listed
        # surname, THEN strip -- and the refusal came first, so the stripping never ran for
        # anything else. "Responsavel Bruno Zanchetta" lost the whole span and published the
        # surname; with an unlisted given name too, "Cliente Wanderleia Kranz" published the
        # entire name. Exactly the labels that precede a name in minutes.
        while len(run) > 1 and (
            _fold(run[0].group(0)) in _NAME_CONNECTIVES
            or _fold(run[0].group(0)) in _COMMON_PHRASE_HEADS
        ):
            run = run[1:]
        if len(run) < 2:
            return None
    return _tighten_to_tokens(offset + run[0].start(), offset + run[-1].end(), text)


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

    Brazilian full names use the very same prepositions, but always AFTER a given name. That
    used to be the whole rule -- unknown head plus a preposition meant "not a person" -- and it
    was backwards: "Nivaldo da Silva", "Zoraide dos Santos", "Marlene da Costa" are the most
    common full-name shape in the country, and the particle that MARKS them switched the shield
    off. So the head has to be RECOGNISED as ordinary vocabulary; an unknown head with a
    surname tail stays a person. See `_COMMON_PHRASE_HEADS`.

    Only the GENITIVE prepositions count -- see `_CONJUNCTION` for why `e` must not.
    """
    if _has_a_person_head(run):
        return False
    if not any(_fold(t.group(0)) in _GENITIVE_PREPOSITIONS for t in run):
        return False
    return _fold(run[0].group(0)) in _COMMON_PHRASE_HEADS


def _ends_on_a_word_boundary(end: int, text: str) -> bool:
    """False when the cut falls in the middle of a word in the FULL text.

    Checked against `text` and not against the match: the cut lands exactly at the end of
    the match, so the character that tells whether it is mid-word is the one after it.

    A combining mark counts as inside the word. `.isalpha()` is False for U+0301 and friends,
    so on NFD-decomposed input -- which macOS filesystems and some ASR exports produce -- the
    guard waved through a cut between a letter and its own accent: NFD("Dr. Antônio Gonçalves")
    came out as "[[PERSON_NAME_1]]̂nio Gonçalves". `redact` normalizes to NFC before any of
    this runs, so that input should never arrive here; this is the second lock, for anything
    that reaches the guard by another route.

    A hyphen followed by a CAPITAL is inside the word too: "Silva-Costa" is one surname, and
    cutting at the hyphen emitted "[[PERSON_NAME_1]]-Costa" -- a corrupted token AND the tail
    in the clear.

    Only a capital. Refusing before any letter turned "Contato Carlos Silva-jr." into a leak of
    the surname: the whole span was rejected, Pattern 3 caught the given name alone, and "Silva"
    went out in the clear where it had been redacted before. A lowercase tail is a suffix or
    the next word, not the other half of a compound surname -- which is exactly what
    `_TITLE_WORD`'s own hyphen branch already says by requiring an uppercase letter after the
    hyphen.
    """
    if end >= len(text):
        return True
    nxt = text[end]
    if nxt.isalpha() or unicodedata.combining(nxt):
        return False
    if nxt != "-":
        return True
    # Only a Title Case continuation is the other half of a compound surname. An ALL-CAPS tail
    # is a department suffix -- "Carlos Silva-TI", "Ana Souza-RH" is a standard speaker label --
    # and refusing the span there dropped it whole, publishing the surname. A lowercase tail is
    # a suffix ("Silva-jr") for the same reason. This mirrors `_TITLE_WORD`'s own hyphen branch,
    # which joins `-[UPPER][lower]+` and nothing else.
    return not re.match(f"-[{_UPPER}][{_LOWER}]", text[end : end + 3])


def _qualify_run(run: list[re.Match[str]], offset: int, text: str) -> tuple[int, int] | None:
    """Accepts a clean stretch as a name, or returns None."""
    # "<thing> do <Person>" -- the run opened with a genitive preposition, which means the
    # negative term that split it OWNS what follows, and what follows is therefore a person.
    #
    # Without this, a product name sitting next to a real name switched the redaction off:
    # "O Protheus do Kleber Zanchetta travou" came out untouched, while the same sentence with
    # "sistema" in place of "Protheus" redacted correctly. The reason is that a negative term
    # forces this qualification path, and "Kleber Zanchetta" is on neither list -- whereas an
    # unsplit match is trusted by `_spans_without_negatives` precisely because most real names
    # are on neither list. "o Protheus do fulano" is everyday speech in these transcripts, and
    # the negative list is full of the words that trigger it: Protheus, Sprint, Backlog, Jira.
    possessive = bool(run) and _fold(run[0].group(0)) in _GENITIVE_PREPOSITIONS

    # An orphan connective at the edge does not sustain a name: "Ana Souza de" -> "Ana Souza".
    # Ordinary vocabulary in front goes the same way, and for the same reason the fast path
    # drops it: "Contrato de Marlene da Costa" was refused whole -- the genitive gate saw an
    # ordinary head and a preposition -- and published the full name, while "Marlene da Costa"
    # on its own redacted. The label in front cannot be what decides.
    stripped_a_label = False
    while run and (
        _fold(run[0].group(0)) in _NAME_CONNECTIVES
        or (len(run) > 1 and _fold(run[0].group(0)) in _COMMON_PHRASE_HEADS)
    ):
        stripped_a_label = stripped_a_label or (_fold(run[0].group(0)) in _COMMON_PHRASE_HEADS)
        run = run[1:]
    while run and _fold(run[-1].group(0)) in _NAME_CONNECTIVES:
        run = run[:-1]
    if not run:
        return None
    if len(run) == 1:
        # A lone token left by the split is a name only if a list says so. Without this,
        # "Sr. Jose Protheus da Silva" came out as "[[PERSON_NAME_1]] Protheus da Silva" --
        # the surname in the clear, because the run around it was one token long. The
        # ordinary-vocabulary list is what keeps a lone "Campos" or "Prazo" out.
        #
        # ...but only when the token was alone to begin with. What survives the stripping of a
        # noun phrase is that phrase's own last word, not a name: "Falta o Relatorio de Vendas
        # do Prado" reduces to "Prado", which is on the surname list and is nobody here.
        if stripped_a_label:
            return None
        lone = _fold(run[0].group(0))
        if lone in _COMMON_PHRASE_HEADS or (
            lone not in _BR_TOP_NAMES and lone not in _BR_TOP_SURNAMES
        ):
            return None
        start, end = offset + run[0].start(), offset + run[0].end()
        return (start, end) if _ends_on_a_word_boundary(end, text) else None
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
    #
    # POSSESSIVE: the run is what a negative term owns ("Protheus do Kleber Zanchetta"), and
    # the owner of a product is usually a person. This is the same trust the fast path extends
    # to an unsplit match, granted here on the strength of the preposition -- and withheld,
    # exactly as there: "O Protheus da Nota Fiscal" and "O Jira do Time Comercial" own a thing,
    # not a person. That is NOT enforced here. Every span this returns is re-qualified by
    # `_qualify_and_claim` over the narrowed slice, where the negative term is gone and
    # `_trusted_span` applies the ordinary-head rule -- so a check here is dead code, and a
    # mutation harness proved it: deleting it changed no output. One mechanism, tested once.
    if not possessive and not _has_a_person_head(run):
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


def _widen_over_hyphens(start: int, end: int, text: str) -> tuple[int, int]:
    """Grows a span over `-letter` on either side, so a hyphenated compound stays whole."""
    while start > 1 and text[start - 1] == "-" and text[start - 2].isalpha():
        start -= 2
        while start > 0 and text[start - 1].isalpha():
            start -= 1
    while end + 1 < len(text) and text[end] == "-" and text[end + 1].isalpha():
        end += 2
        while end < len(text) and text[end].isalpha():
            end += 1
    return start, end


def _caps_name_spans(match: re.Match[str], text: str) -> list[tuple[int, int]]:
    """The stretches of an ALL-CAPS run that are names, at most one per allow-list-free stretch.

    Returns a list for the same reason `_spans_without_negatives` does: an allow-listed term
    inside the match separates two candidates rather than cancelling both. "SAP CARLOS SILVA"
    is a product and a person, and returning None for the pair -- which is what this did until
    finding 5a -- published the person.
    """
    # There is deliberately NO guard against overlapping a placeholder from the basic-pattern
    # stage. One was written, and three independent reviewers proved it unreachable: a
    # placeholder is "[[TYPE_N]]", and `_CAPS_SEQUENCE_RE` needs `\s+` between its words, which
    # the "_1]] [[" between two placeholders is not. Keeping it meant a test that certified a
    # control that cannot fire -- the defect class this file has been chasing for five rounds.
    # `test_a_placeholder_from_the_earlier_stage_is_not_read_as_an_all_caps_name` still asserts
    # the OUTCOME end to end, which is the part that is real.
    spans: list[tuple[int, int]] = []
    for run in _split_on_allow_list(list(_WORD_RE.finditer(match.group(0)))):
        span = _caps_span_for_run(run, match.start(), text)
        if span is not None:
            spans.append(span)
    return spans


def _caps_span_for_run(tokens: list[re.Match[str]], base: int, text: str) -> tuple[int, int] | None:
    """The stretch of one allow-list-free ALL-CAPS run that is a name, or None.

    The run must NOT be claimed whole. "CARLOS SILVA APROVOU O ESCOPO" matched three tokens and
    took all of them: the verb vanished from the text the model reads -- the decision itself --
    and the record was filed under `_hash("CARLOS SILVA APROVOU")`, which is nobody.

    Unlike the Title Case sequence this pattern is not trusted on its shape, because an
    all-caps pair is more often an acronym string or a section heading than a person. Both ends
    have to be recognised: it opens on a listed given name and closes on a listed surname. That
    is also what keeps "ACOMPANHAMENTO DE CAMPOS" and "LISTA DE CAMPOS" out, which the previous
    head-OR-tail rule read as people -- a third of the surname list doubles as an ordinary
    Portuguese noun.

    `base` is where the enclosing match starts, since the token offsets are relative to it.
    """
    for i, head in enumerate(tokens):
        folded_head = _fold(head.group(0))
        if folded_head in _NAME_CONNECTIVES or folded_head in _COMMON_PHRASE_HEADS:
            # Ordinary vocabulary never opens a name, and skipping it is what lets the name
            # be found FURTHER IN: "FICOU COM EDSON SILVA" yields EDSON SILVA.
            continue
        head_is_a_given_name = folded_head in _BR_TOP_NAMES

        # Longest first: "JOAO PEDRO COSTA" is one person, not "JOAO PEDRO" plus a stray.
        for j in range(len(tokens) - 1, i, -1):
            # The tail is what bounds the span. Accepting any tail once the head was a known
            # given name is how "CARLOS SILVA APROVOU" kept the verb: with the head
            # recognised, the longest-first loop took the furthest token whatever it was.
            if _fold(tokens[j].group(0)) not in _BR_TOP_SURNAMES:
                continue
            # A particle inside the span is the commonest full-name shape there is, but only a
            # recognised given name makes it a name rather than a noun phrase: "PEDRO DA SILVA"
            # is a person, "LISTA DE CAMPOS" is not.
            if not head_is_a_given_name and any(
                _fold(tokens[k].group(0)) in _GENITIVE_PREPOSITIONS for k in range(i, j + 1)
            ):
                continue
            start, end = base + head.start(), base + tokens[j].end()
            if _ends_on_a_word_boundary(end, text):
                return start, end

        # A listed given name plus ONE more token. Requiring the tail to be a listed surname
        # too left every all-caps name with an off-list surname in the clear -- "ANA
        # BITTENCOURT", "MARINA KRANZ", "CARLOS HOFFMANN" -- and measured against the corpus
        # that was the ENTIRE residual leak: 240 of 240. One token, so a sentence cannot be
        # swallowed, and not a verb: a third-person preterite is what follows a name in
        # minutes ("APROVOU", "ASSUMIU"), and no Brazilian surname ends that way.
        if head_is_a_given_name and i + 1 < len(tokens):
            following = _fold(tokens[i + 1].group(0))
            if (
                following not in _COMMON_PHRASE_HEADS
                and following not in _NAME_CONNECTIVES
                and not _VERB_TAIL_RE.search(following)
            ):
                start, end = base + head.start(), base + tokens[i + 1].end()
                if _ends_on_a_word_boundary(end, text):
                    return start, end
    return None


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
    has_at = "@" in text
    for pii_type, pattern in _BASIC_PATTERNS:
        if pattern is _EMAIL_RE and not has_at:
            continue
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
    # Ranges already consumed, KEPT SORTED by start. Claims never overlap -- every path checks
    # `_is_covered` first -- so the list is a set of disjoint intervals and a binary search
    # answers both questions below.
    #
    # This was a plain list with a linear scan, which made `redact` quadratic in the number of
    # claims. `AnalyzeRequest.transcript` allows 1_000_000 chars and `routers/analyze.py` calls
    # this synchronously: measured on name-dense pt-BR text the cost went 0.13s at 25KB,
    # 0.45s at 50KB, 1.84s at 100KB, 6.76s at 200KB -- 4x per doubling, with 37 million
    # generator steps inside `_is_covered` alone at 100KB. A legal-size request occupied a
    # worker thread for over a minute of pure CPU before the LLM call even started.
    covered: list[tuple[int, int]] = []

    def _is_covered(start: int, end: int) -> bool:
        # First interval that begins at or after `start`; only it and its predecessor can
        # overlap [start, end), the intervals being disjoint and sorted.
        i = bisect.bisect_left(covered, (start, -1))
        if i < len(covered) and covered[i][0] < end:
            return True
        return i > 0 and covered[i - 1][1] > start

    def _claim(start: int, end: int, value: str) -> None:
        person_matches.append(_Match(type=PiiType.PERSON_NAME, start=start, end=end, value=value))
        bisect.insort(covered, (start, end))

    def _claim_free_parts(start: int, end: int) -> None:
        """Claims the candidate, trimming what is already covered instead of discarding it.

        `_is_covered` alone is all-or-nothing, and that started losing names when
        Pattern 1 began claiming TRIMMED spans: a Pattern 2 match that was entirely
        clean and merely TOUCHED that trim was thrown away whole, and the surname
        went out in the clear. Here whatever is free remains, and each free piece is
        validated again as a name before being claimed.
        """
        cursor = start
        # Start at the last interval that could still reach `start`, not at the beginning.
        i = max(0, bisect.bisect_left(covered, (start, -1)) - 1)
        for cs, ce in covered[i:]:
            if cs >= end:
                break
            if ce <= cursor:
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
    widened_through = -1
    for m in _NAME_TOKEN_RE.finditer(text):
        # Every token inside a hyphenated compound widens to the SAME compound, so widening
        # each of them walks the whole chain again: `redact` went quadratic and 77KB of
        # "Ana-B-Ana-B-..." took 51s, which extrapolates to hours at the 1_000_000-char
        # transcript cap that `routers/analyze.py` hands straight to this function.
        if m.start() < widened_through:
            continue
        # Widened to the whole hyphenated compound before anything is decided. `_TITLE_WORD`
        # only joins a hyphen to an UPPERCASE continuation, so "Maria-do-Carmo" matched just
        # "Maria" and the placeholder went out spliced -- "[[PERSON_NAME_1]]-do-Carmo". And
        # since the compound is one token, "Ana-Paula" was tested against the list as
        # "ana-paula", which is on no list of single given names, so a full name that used to
        # be redacted (as two separate tokens) leaked whole.
        start, end = _widen_over_hyphens(m.start(), m.end(), text)
        widened_through = end
        if _is_covered(start, end):
            continue
        compound = text[start:end]
        parts = [_fold(p.group(0)) for p in _WORD_RE.finditer(compound)]
        if not any(p in _BR_TOP_NAMES for p in parts):
            continue
        if any(p in _PERSON_NAME_NEGATIVE_LIST for p in parts):
            continue
        _claim(start, end, compound)

    # Pattern 4: ALL-CAPS full name ("CARLOS SILVA: fechamos o escopo").
    #
    # Speaker labels and attendee lists come upper-cased out of most diarisers and out of
    # ordinary minute-taking, and this shield is fed the raw transcript. None of the patterns
    # above can see them: `_TITLE_WORD` requires lowercase after the first letter, so an
    # all-caps full name matched nothing at all and went to the model whole.
    #
    # Unlike Pattern 2 this one is NOT trusted on its shape: an all-caps pair is more often an
    # acronym string ("NOTA FISCAL", "CRM ERP") than a person, so one end has to be on the
    # name lists. That is the same head-or-tail test `_qualify_run` applies, used here as the
    # admission rule rather than as a fallback.
    for m in _CAPS_SEQUENCE_RE.finditer(text):
        for span in _caps_name_spans(m, text):
            if _is_covered(span[0], span[1]):
                continue
            _claim(span[0], span[1], text[span[0] : span[1]])

    # Pattern 5: a one-word ALL-CAPS speaker label, "MARINA: fechamos o escopo". Pattern 4
    # needs two tokens, so this shape matched nothing. Runs after it, so "CARLOS SILVA:" is
    # already claimed whole and only a genuinely lone label reaches here.
    for m in _CAPS_LABEL_RE.finditer(text):
        token = _fold(m.group(0))
        if token not in _BR_TOP_NAMES or token in _PERSON_NAME_NEGATIVE_LIST:
            continue
        if _is_covered(m.start(), m.end()):
            continue
        _claim(m.start(), m.end(), m.group(0))

    # Pattern 6: a speaker label opening a line. The only shape where neither end has to be
    # recognised -- the position and the colon carry it. Runs last, so anything the lists could
    # identify is already claimed and only the unrecognised names reach here.
    #
    # The false positives concentrate on the ONE-token label, and structurally so: the guard is a
    # per-token blocklist, so a three-word heading gets three chances of hitting it while a
    # one-word heading gets one. The pattern is left as it is anyway. Requiring two tokens here
    # would hand every single-word all-caps label written by an unlisted person straight to the
    # provider -- Pattern 5 above only covers labels that ARE on the given-name list -- and that
    # leak is what this pattern was added to close. A missed heading is fixed by naming it in
    # `_COMMON_PHRASE_HEADS`, not by narrowing the shape.
    for m in _CAPS_SPEAKER_RE.finditer(text):
        label = m.group(1)
        tokens = [_fold(t.group(0)) for t in _WORD_RE.finditer(label)]
        if any(
            t in _PERSON_NAME_NEGATIVE_LIST
            or t in _COMMON_PHRASE_HEADS
            or t in _NAME_CONNECTIVES
            or _VERB_TAIL_RE.search(t)
            for t in tokens
        ):
            continue
        start, end = m.start(1), m.end(1)
        if _is_covered(start, end):
            continue
        _claim(start, end, label)

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

    The input is normalized to NFC first. Decomposed text -- `A` + U+0301 rather than `Á` --
    arrives from macOS filesystems and from some ASR exports, and every pattern here assumes
    one code point per letter: `\\b` and `.isalpha()` both treat a combining mark as a
    boundary, so NFD("Dr. Antônio Gonçalves aprovou.") came out as
    "[[PERSON_NAME_1]]̂nio Gonçalves aprovou." -- the placeholder spliced into the name, the
    rest of it in the clear, and `_hash` filing a value nobody wrote. pt-BR names are
    accent-dense, so this is not an edge case in this corpus. NFC is also what the redacted
    text should be on the way to the model.
    """
    text = unicodedata.normalize("NFC", text)
    intermediate, basic_redactions, counters = _apply_basic_patterns(text)
    final_text, person_redactions = _redact_person_names(intermediate, counters)
    return PiiRedactionV1(
        redactedText=final_text,
        redactions=basic_redactions + person_redactions,
    )
