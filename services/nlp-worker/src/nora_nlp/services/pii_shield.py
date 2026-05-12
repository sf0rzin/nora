"""PII Shield baseline: regex deterministico aplicado antes de qualquer chamada ao LLM.

Cobre os tipos basicos brasileiros (e-mail, telefone, CPF, CNPJ, cartao) e detecta
nomes proprios via regex heuristico + lista hardcoded de top nomes BR + negative list
de termos tecnicos/produtos/empresas. ADDRESS fica para uma fatia futura.

A estrategia de PERSON_NAME e detalhada na docstring de `_redact_person_names`.
"""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from dataclasses import dataclass

from ..models import PiiRedactionV1, PiiType, Redaction

# --------------------------------------------------------------------------- #
# Padroes deterministicos: e-mail, CPF, CNPJ, cartao, telefone
# --------------------------------------------------------------------------- #

_EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}")
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?55\s?)?\(?\d{2}\)?[\s.\-]?\d{4,5}[\s.\-]?\d{4}(?!\d)")
_CPF_RE = re.compile(r"(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")
_CNPJ_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}(?!\d)")
_CARD_RE = re.compile(r"(?<!\d)(?:\d{4}[\s\-]?){3}\d{4}(?!\d)")

_BASIC_PATTERNS: list[tuple[PiiType, re.Pattern[str]]] = [
    (PiiType.EMAIL, _EMAIL_RE),
    (PiiType.CPF, _CPF_RE),
    (PiiType.CNPJ, _CNPJ_RE),
    (PiiType.CREDIT_CARD, _CARD_RE),
    (PiiType.PHONE, _PHONE_RE),
]


# --------------------------------------------------------------------------- #
# PERSON_NAME -- listas e padroes
# --------------------------------------------------------------------------- #

# Top primeiros nomes brasileiros (mix masculino + feminino + variantes comuns).
# Curado a partir de levantamentos do IBGE/Censo + nomes recorrentes em transcricoes
# corporativas pt-BR. Lista finita: cada termo serve como ancora para acionar o
# padrao "nome isolado" (sem sobrenome). Sobrenomes nao precisam estar aqui --
# `_NAME_SEQUENCE_RE` cobre "Marina Alves" via Title Case.
_BR_TOP_NAMES: frozenset[str] = frozenset(
    name.casefold()
    for name in (
        # Masculinos
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
        # Femininos
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

# Termos que se parecem com nomes (Title Case ou primeiro nome) mas designam
# produtos, empresas, tecnologias, frameworks ou conceitos -- jamais redigir.
# Usado como filtro pos-match: se qualquer token do candidato bater aqui
# (case-insensitive), descarta o match inteiro.
_PERSON_NAME_NEGATIVE_LIST: frozenset[str] = frozenset(
    term.casefold()
    for term in (
        # Produtos TOTVS e correlatos
        "TOTVS",
        "Protheus",
        "RM",
        "Fluig",
        "Logix",
        "Datasul",
        "Backoffice",
        # ERP/CRM concorrentes
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
        # Big tech / nuvem
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
        # Ferramentas dev
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
        # Stack tecnica
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
        "Oracle",
        "MySQL",
        "Redis",
        "Kafka",
        # Produto/empresa NORA
        "NORA",
        "Nora",
        "Acme",
        # Termos genericos que podem aparecer Title Case e nao sao nomes
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


# Prefixo (pronomes de tratamento) seguido de 1-4 palavras Title Case.
# Captura "Dr. Carlos Silva", "Sra. Marina Alves", "Profa. Ana", etc.
# O grupo inteiro (incluindo o prefixo) eh o match.
_NAME_PREFIX_RE = re.compile(
    r"\b(?:Sr|Sra|Srta|Dr|Dra|Prof|Profa)\.\s+"
    r"[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){0,3}"
)

# 2-4 palavras Title Case consecutivas. Cada palavra precisa comecar com letra
# maiuscula (incluindo acentuadas) seguida de >=1 letra minuscula. Tokens em
# all-caps ("TOTVS", "RM") sao ignorados por nao terem lowercase no final --
# evita capturar acronimos como parte de um nome composto.
_NAME_SEQUENCE_RE = re.compile(
    r"\b[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){1,3}\b"
)

# Primeiro nome BR isolado: \b<palavra>\b, case-insensitive, comparado contra
# `_BR_TOP_NAMES`. Construir um regex enorme com alternancias e custoso --
# preferimos varrer todas as palavras alfabeticas e checar contra o set.
_WORD_RE = re.compile(r"\b[A-Za-zÁÉÍÓÚÂÊÔÀÃÕÇáéíóúâêôàãõç]+\b")


# --------------------------------------------------------------------------- #
# Dataclasses e utilitarios
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
    """Quebra um candidato a nome em tokens alfabeticos (descarta prefixos com ponto)."""
    return [tok for tok in _WORD_RE.findall(value)]


def _is_negative(value: str) -> bool:
    """Retorna True se qualquer token do candidato bater na negative list."""
    return any(tok.casefold() in _PERSON_NAME_NEGATIVE_LIST for tok in _tokenize(value))


def _apply_basic_patterns(
    text: str,
) -> tuple[str, list[Redaction], dict[PiiType, int]]:
    """Aplica os 5 padroes deterministicos (email/CPF/CNPJ/cartao/telefone).

    Retorna `(texto_intermediario, redactions, counters)`. O texto intermediario
    ja contem placeholders no lugar de cada match -- e sobre ele que a etapa de
    PERSON_NAME opera, garantindo que substrings de PII basico nao virem nomes
    falsos.
    """
    matches: list[_Match] = []
    for pii_type, pattern in _BASIC_PATTERNS:
        for m in pattern.finditer(text):
            matches.append(_Match(type=pii_type, start=m.start(), end=m.end(), value=m.group(0)))

    matches.sort(key=lambda x: x.start)

    counters: dict[PiiType, int] = defaultdict(int)
    redactions: list[Redaction] = []
    rebuilt: list[str] = []
    cursor = 0

    for m in matches:
        if m.start < cursor:  # overlap entre tipos -- ignora o segundo
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
    """Detecta e redige nomes proprios sobre `text` (ja parcialmente redigido).

    Aplica em ordem tres heuristicas, sempre pulando ranges ja cobertos:

    1. Prefixo + Title Case (`Dr. Carlos Silva`).
    2. 2-4 palavras Title Case consecutivas (`Marina Alves`).
    3. Primeiro nome BR isolado contra a lista hardcoded (`Lucas`, `Marina`).

    A negative list filtra produtos/empresas/tecnologias antes de gerar o
    placeholder. Cada ocorrencia recebe um numero novo (sem dedup -- decisao
    explicita do escopo).
    """
    person_matches: list[_Match] = []
    covered: list[tuple[int, int]] = []  # ranges (start, end) ja consumidos

    def _is_covered(start: int, end: int) -> bool:
        return any(not (end <= cs or start >= ce) for cs, ce in covered)

    def _claim(start: int, end: int, value: str) -> None:
        person_matches.append(_Match(type=PiiType.PERSON_NAME, start=start, end=end, value=value))
        covered.append((start, end))

    # Padrao 1: prefixo
    for m in _NAME_PREFIX_RE.finditer(text):
        if _is_covered(m.start(), m.end()):
            continue
        if _is_negative(m.group(0)):
            continue
        _claim(m.start(), m.end(), m.group(0))

    # Padrao 2: sequencia Title Case (2-4 palavras)
    for m in _NAME_SEQUENCE_RE.finditer(text):
        if _is_covered(m.start(), m.end()):
            continue
        if _is_negative(m.group(0)):
            continue
        _claim(m.start(), m.end(), m.group(0))

    # Padrao 3: primeiro nome BR isolado
    for m in _WORD_RE.finditer(text):
        if _is_covered(m.start(), m.end()):
            continue
        token = m.group(0)
        if token.casefold() not in _BR_TOP_NAMES:
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
    """Substitui PII por placeholders no formato `[[TIPO_N]]`.

    Fluxo em duas etapas:
      1. Padroes deterministicos basicos (email/CPF/CNPJ/cartao/telefone).
      2. Heuristicas de PERSON_NAME sobre o texto ja parcialmente redigido --
         garante que ex. um nome dentro de um e-mail nao sera redigido de novo.
    """
    intermediate, basic_redactions, counters = _apply_basic_patterns(text)
    final_text, person_redactions = _redact_person_names(intermediate, counters)
    return PiiRedactionV1(
        redactedText=final_text,
        redactions=basic_redactions + person_redactions,
    )
