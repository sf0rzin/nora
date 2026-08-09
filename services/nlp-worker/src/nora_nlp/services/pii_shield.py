"""PII Shield baseline: regex deterministico aplicado antes de qualquer chamada ao LLM.

Cobre os tipos basicos brasileiros (e-mail, telefone, CPF, CNPJ, cartao) e detecta
nomes proprios via regex heuristico + lista hardcoded de top nomes BR + negative list
de termos tecnicos/produtos/empresas. ADDRESS fica para uma fatia futura.

A estrategia de PERSON_NAME e detalhada na docstring de `_redact_person_names`.
"""

from __future__ import annotations

import hashlib
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from ..models import PiiRedactionV1, PiiType, Redaction


def _fold(value: str) -> str:
    """Normaliza para comparacao insensivel a ACENTO e caixa (NFKD + casefold).

    CRITICO p/ PERSON_NAME: a lista `_BR_TOP_NAMES` e escrita SEM acento, mas
    transcricoes reais trazem nomes acentuados (Patrícia, Antônio, André, João).
    Sem o accent-fold, "Patrícia".casefold() ("patrícia") nunca casava com
    "Patricia".casefold() ("patricia") da lista -> o nome vazava cru pro LLM.
    Bug real encontrado em producao (jun/2026). ADR 0012.
    """
    return "".join(
        c for c in unicodedata.normalize("NFKD", value) if not unicodedata.combining(c)
    ).casefold()


# --------------------------------------------------------------------------- #
# Padroes deterministicos: e-mail, CPF, CNPJ, cartao, telefone
# --------------------------------------------------------------------------- #

# Email com (?<!\w) ancorando a esquerda evita catastrophic backtracking em
# inputs grandes (medido empiricamente: 100KB de input era ~10s; com a ancora
# bate microsegundos). Pre-filtro `'@' in text` acelera em entradas sem email.
_EMAIL_RE = re.compile(r"(?<![\w@])[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b")

# Telefones BR: com DDD obrigatorio (8 ou 9 digitos apos DDD). Conservador
# de proposito: telefone sem DDD (98765-4321) e pequeno demais pra distinguir
# de codigos/protocolos numericos sem falso-positivo massivo.
#
# Tolerancias (auditoria 2026-06-16, ADR 0012) -- todas SEM relaxar o
# requisito de DDD (telefone nao tem DV, entao DDD obrigatorio segura o FP):
#   - `(?:\+?55[\s.\-]?)?`        prefixo +55 internacional opcional.
#   - `\(?\s*0?\d{2}\s*\)?`       parenteses com espaco interno ("( 11 )") e
#                                 DDD com zero antigo de 3 digitos ("(011)").
#   - `(?:9[\s.\-/]?)?`           9o digito do celular ditado SOLTO entre o DDD
#                                 e o numero ("(11) 9 8765-4321") -- comum em
#                                 transcricao speech-to-text.
#   - `[\s.\-/]`                  separador `/` ("11/98765/4321") alem de espaco/
#                                 ponto/hifen.
# DEFERIDO (alto risco de FP sem DV -- ficam para uma fatia futura):
#   - telefone SEM DDD ("99988-7766", "3003-1234"): pequeno demais p/ distinguir
#     de codigos/protocolos numericos.
#   - internacional NAO-BR ("+1 415 555 2671"): generalizar `\+\d{1,3}` explode FP.
_PHONE_RE = re.compile(
    r"(?<!\d)(?:\+?55[\s.\-]?)?\(?\s*0?\d{2}\s*\)?[\s.\-/]?(?:9[\s.\-/]?)?\d{4,5}[\s.\-/]?\d{4}(?!\d)"
)

# CPF mascarado.
_CPF_RE = re.compile(r"(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")
# CPF raw (11 digitos sem mascara). Validacao de DV no `_validate_cpf` filtra
# falsos positivos (ex.: 12345678900 nao passa).
_CPF_RAW_RE = re.compile(r"(?<!\d)\d{11}(?!\d)")
# CPF parcialmente mascarado (so com hifen, sem pontos): "12345678-09".
_CPF_PARTIAL_RE = re.compile(r"(?<!\d)\d{8}-\d{2}(?!\d)")
# CPF com grupos separados por ESPACO: "111 444 777 35" (3-3-3-2). A validacao de
# DV (apos remover os espacos) evita redigir sequencias numericas aleatorias.
_CPF_SPACED_RE = re.compile(r"(?<!\d)\d{3}\s\d{3}\s\d{3}\s\d{2}(?!\d)")
# CPF TOLERANTE a separadores arbitrarios (auditoria 2026-06-16): 11 digitos
# em grupos 3-3-3-2 com QUALQUER separador da classe `[.\-/\s]` entre cada grupo.
# Cobre "111.444.777 35" (misto), "111/444/777-35" (barra) e "111-444-777-35"
# (so hifen) -- formatos que os patterns rigidos acima nao casavam. O DV
# (`_validate_cpf_separated`) e o gate: regex tolerante so vira redacao se o
# digito verificador fechar, entao o FP em sequencias numericas e ~zero.
# Superset dos patterns acima (que ficam por clareza/regressao; overlap-skip dedupe).
_CPF_SEP_RE = re.compile(r"(?<!\d)\d{3}[.\-/\s]\d{3}[.\-/\s]\d{3}[.\-/\s]\d{2}(?!\d)")

# CNPJ mascarado.
_CNPJ_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}(?!\d)")
# CNPJ raw (14 digitos sem mascara). Validacao de DV filtra falsos positivos.
_CNPJ_RAW_RE = re.compile(r"(?<!\d)\d{14}(?!\d)")
# CNPJ com grupos separados por ESPACO: "11 222 333 0001 81" (2-3-3-4-2). A
# validacao de DV (apos remover os espacos) filtra falsos positivos.
_CNPJ_SPACED_RE = re.compile(r"(?<!\d)\d{2}\s\d{3}\s\d{3}\s\d{4}\s\d{2}(?!\d)")
# CNPJ com PONTO entre TODOS os grupos: "11.222.333.0001.81" (2.3.3.4.2). Os
# patterns acima exigem `/` + `-` (mascara canonica) ou espaco; "so pontos" nao
# casava (auditoria 2026-06-16). DV (`_validate_cnpj_separated`) e o gate.
_CNPJ_DOTS_RE = re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}\.\d{4}\.\d{2}(?!\d)")

# Cartoes — Amex tem 15 digitos com prefixo 34/37; demais tem 16 com 4x4.
# Separadores aceitos: espaco, hifen e PONTO ("4111.1111.1111.1111"). A
# validacao de Luhn (`_validate_card`, apos remover separadores) reduz drasticamente
# o falso-positivo de qualquer sequencia generica de 16 digitos.
_CARD_AMEX_RE = re.compile(r"(?<!\d)3[47]\d{2}[\s.\-]?\d{6}[\s.\-]?\d{5}(?!\d)")
_CARD_RE = re.compile(r"(?<!\d)(?:\d{4}[\s.\-]?){3}\d{4}(?!\d)")
# Diners Club (e algumas UnionPay): 14 digitos em grupos 4-4-4-2
# ("3056 9309 0259 04"). O _CARD_RE generico so casa 16 digitos (4x4); este
# cobre o comprimento 14. Luhn (`_validate_card`, agora aceitando len 14) e o
# gate. A ancora `(?!\d)` impede casar um prefixo de cartao de 16 digitos.
_CARD_DINERS_RE = re.compile(r"(?<!\d)\d{4}[\s.\-]?\d{4}[\s.\-]?\d{4}[\s.\-]?\d{2}(?!\d)")


def _validate_cpf(digits: str) -> bool:
    """Valida CPF de 11 digitos via DV. Rejeita sequencias triviais (000.000.000-00 etc.)."""
    if len(digits) != 11 or not digits.isdigit():
        return False
    if digits == digits[0] * 11:  # 000... ate 999... — invalidos
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
    """Valida CNPJ de 14 digitos via DV."""
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
    """Checa o digito verificador de Luhn (mod 10) de uma sequencia de digitos."""
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
    """Remove tudo que nao for digito (espaco, ponto, hifen, barra)."""
    return re.sub(r"\D", "", value)


def _validate_card(value: str) -> bool:
    """Valida um candidato a cartao: 14 (Diners), 15 (Amex) ou 16 digitos + Luhn.

    Recebe o match cru (com separadores espaco/ponto/hifen) e normaliza antes do
    Luhn. Reduz falso-positivo de qualquer sequencia generica de 14-16 digitos
    (codigos de pedido, rastreio, NFE) que nao passa no digito verificador.
    Comprimento 14 cobre Diners Club e algumas faixas UnionPay (auditoria 2026-06-16).
    """
    digits = _strip_separators(value)
    if len(digits) not in (14, 15, 16):
        return False
    return _luhn_ok(digits)


def _validate_cpf_separated(value: str) -> bool:
    """Valida CPF cujo match contem separadores (espaco): normaliza e checa DV."""
    return _validate_cpf(_strip_separators(value))


def _validate_cnpj_separated(value: str) -> bool:
    """Valida CNPJ cujo match contem separadores (espaco): normaliza e checa DV."""
    return _validate_cnpj(_strip_separators(value))


# Ordem importa: mascarados/cartoes primeiro (regex mais especifico), depois raw
# com DV check (CPF/CNPJ raw), por ultimo telefone (mais ambiguo). Sem essa ordem,
# PHONE_RE consome 11 digitos antes do CPF_RAW_RE poder identificar — tipo errado
# no placeholder mas mesmo grau de protecao.
#
# `_apply_basic_patterns` filtra raw/separado via `_validate_*`. Cartoes exigem
# Luhn valido (`_validate_card`). Cartao Amex (15 digitos) vem ANTES do _CARD_RE
# generico (16 digitos com 4x4). Os patterns separados por espaco (CPF/CNPJ) vem
# junto dos mascarados, mas so sao aceitos com DV valido.
_BASIC_PATTERNS: list[tuple[PiiType, re.Pattern[str]]] = [
    (PiiType.EMAIL, _EMAIL_RE),
    (PiiType.CPF, _CPF_RE),
    (PiiType.CPF, _CPF_PARTIAL_RE),
    (PiiType.CPF, _CPF_SPACED_RE),  # "111 444 777 35" — exige DV
    (PiiType.CPF, _CPF_SEP_RE),  # "111.444.777 35" / "111/444/777-35" / "111-444-777-35" — exige DV
    (PiiType.CNPJ, _CNPJ_RE),
    (PiiType.CNPJ, _CNPJ_SPACED_RE),  # "11 222 333 0001 81" — exige DV
    (PiiType.CNPJ, _CNPJ_DOTS_RE),  # "11.222.333.0001.81" — exige DV
    (PiiType.CREDIT_CARD, _CARD_AMEX_RE),
    (PiiType.CREDIT_CARD, _CARD_RE),
    (PiiType.CREDIT_CARD, _CARD_DINERS_RE),  # "3056 9309 0259 04" (14 dig) — exige Luhn
    (PiiType.CNPJ, _CNPJ_RAW_RE),  # raw com DV — antes de PHONE pra ganhar prioridade
    (PiiType.CPF, _CPF_RAW_RE),
    (PiiType.PHONE, _PHONE_RE),
]

# Patterns que exigem validacao (DV ou Luhn) para serem aceitos. Cartoes usam
# Luhn; CPF/CNPJ (raw ou separados por espaco) usam digito verificador.
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
# PERSON_NAME -- listas e padroes
# --------------------------------------------------------------------------- #

# Top primeiros nomes brasileiros (mix masculino + feminino + variantes comuns).
# Curado a partir de levantamentos do IBGE/Censo + nomes recorrentes em transcricoes
# corporativas pt-BR. Lista finita: cada termo serve como ancora para acionar o
# padrao "nome isolado" (sem sobrenome). Sobrenomes nao precisam estar aqui --
# `_NAME_SEQUENCE_RE` cobre "Marina Alves" via Title Case.
_BR_TOP_NAMES: frozenset[str] = frozenset(
    _fold(name)
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
    _fold(term)
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
        # Stack tecnica (Oracle ja listado em "ERP/CRM concorrentes" acima)
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


# Prefixo (pronomes de tratamento + cargos) seguido de 1-5 palavras Title Case,
# suportando conectivos PT-BR (`da`, `de`, `do`, `das`, `dos`, `e`).
# Captura "Dr. Carlos Silva", "Sra. Marina Alves", "Profa. Ana de Souza",
# "Sr. Jose da Silva Pereira", "Eng. Joao", "Diretor Carlos da Silva", etc.
_NAME_PREFIX_RE = re.compile(
    r"\b(?:Sr|Sra|Srta|Dr|Dra|Prof|Profa|Eng|Engenheiro|Engenheira|"
    r"Cap|Sgt|Gen|Pe|Padre|Diretor|Diretora|Coordenador|Coordenadora|"
    r"Gerente|Pres|Presidente)\.?\s+"
    r"[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+(?:d[aeo]s?|e)\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"|\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){0,4}"
)

# 2-5 palavras Title Case consecutivas, com suporte a conectivos PT-BR
# (`da`, `de`, `do`, `das`, `dos`, `e`) entre tokens. "Jose da Silva Pereira"
# casa como um unico nome composto em vez de dois separados. Tokens em
# all-caps ("TOTVS", "RM") sao ignorados por nao terem lowercase no final --
# evita capturar acronimos como parte de um nome composto.
_NAME_SEQUENCE_RE = re.compile(
    r"\b[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"(?:\s+(?:d[aeo]s?|e)\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+"
    r"|\s+[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+){1,4}\b"
)

# Token alfabetico generico (usado por `_tokenize` para a negative list — pode
# ser case-insensitive porque so checamos contra negative list que tambem usa
# casefold).
_WORD_RE = re.compile(r"\b[A-Za-zÁÉÍÓÚÂÊÔÀÃÕÇáéíóúâêôàãõç]+\b")

# Primeiro nome BR isolado: SO casa Title Case (`Joao`, `Marina`). Casar
# minusculas (`joao`, `rosa`, `clara`) gera falso-positivo massivo em
# substantivos comuns do PT-BR. Em transcricoes corporativas reais, primeiros
# nomes sempre aparecem Title Case (capitalizacao automatica do dicador) ou
# all-caps no contexto formal (que sao filtrados por `_PERSON_NAME_NEGATIVE_LIST`).
_NAME_TOKEN_RE = re.compile(r"\b[A-ZÁÉÍÓÚÂÊÔÀÃÕÇ][a-záéíóúâêôàãõç]+\b")


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
    return any(_fold(tok) in _PERSON_NAME_NEGATIVE_LIST for tok in _tokenize(value))


_NAME_CONNECTIVES: frozenset[str] = frozenset(
    _fold(c) for c in ("da", "de", "do", "das", "dos", "e")
)

# Pronomes de tratamento e cargos aceitos por `_NAME_PREFIX_RE`. Repetidos aqui como
# conjunto porque, no caminho de recorte abaixo, o prefixo e o proprio sinal de que o
# trecho restante e gente ("Dr. Carlos" continua valendo depois de tirar um produto).
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


def _span_without_negatives(value: str, offset: int) -> tuple[int, int] | None:
    """Devolve o trecho do candidato que ainda vale como nome, ou None.

    Sem nenhum token da negative list, o match passa inteiro -- comportamento de sempre.

    Com token da lista, o tratamento anterior era all-or-nothing: `_is_negative` respondia
    True para QUALQUER token e o chamador descartava o match todo. Como `_NAME_SEQUENCE_RE`
    e guloso (2-5 palavras Title Case ligadas por conectivos PT-BR), bastava encostar um
    nome de produto no da pessoa -- "Ana Souza Protheus" -- para "Ana Souza" sair em claro.

    Aqui o ofensor e removido e fica o maior trecho contiguo limpo, mas so se ele tiver um
    sinal proprio de pessoa: o primeiro token precisa ser um primeiro nome BR conhecido ou
    um pronome de tratamento. Sem essa exigencia o recorte viraria falso-positivo em nome
    composto de empresa -- "Acme Software Solutions" (Acme na lista) redigiria
    "Software Solutions" como se fosse gente.

    Devolve None quando nada sobra util, inclusive com todos os tokens negativos
    ("TOTVS Protheus" segue intocado). Token solto fica por conta do Padrao 3.
    """
    tokens = list(_WORD_RE.finditer(value))
    if not tokens:
        return None

    if not any(_fold(t.group(0)) in _PERSON_NAME_NEGATIVE_LIST for t in tokens):
        return offset, offset + len(value)

    best: list[re.Match[str]] = []
    run: list[re.Match[str]] = []
    for tok in tokens:
        if _fold(tok.group(0)) in _PERSON_NAME_NEGATIVE_LIST:
            best = max(best, run, key=len)
            run = []
        else:
            run.append(tok)
    best = max(best, run, key=len)

    # Conectivo orfao na ponta nao sustenta nome: "Ana Souza de" -> "Ana Souza".
    while best and _fold(best[0].group(0)) in _NAME_CONNECTIVES:
        best = best[1:]
    while best and _fold(best[-1].group(0)) in _NAME_CONNECTIVES:
        best = best[:-1]

    if len(best) < 2:
        return None
    head = _fold(best[0].group(0))
    if head not in _BR_TOP_NAMES and head not in _NAME_HONORIFICS:
        return None
    return offset + best[0].start(), offset + best[-1].end()


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
        validator = _VALIDATORS.get(id(pattern))
        for m in pattern.finditer(text):
            raw = m.group(0)
            # Pattern raw (CPF/CNPJ sem mascara): exige DV valido para evitar
            # false positives em codigos numericos genericos (ID de pedido,
            # rastreio, NFE etc.).
            if validator is not None and not validator(raw):
                continue
            matches.append(_Match(type=pii_type, start=m.start(), end=m.end(), value=raw))

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
        span = _span_without_negatives(m.group(0), m.start())
        if span is None:
            continue
        start, end = span
        if _is_covered(start, end):
            continue
        _claim(start, end, text[start:end])

    # Padrao 2: sequencia Title Case (2-4 palavras)
    for m in _NAME_SEQUENCE_RE.finditer(text):
        span = _span_without_negatives(m.group(0), m.start())
        if span is None:
            continue
        start, end = span
        if _is_covered(start, end):
            continue
        _claim(start, end, text[start:end])

    # Padrao 3: primeiro nome BR isolado (Title Case, contra lista hardcoded).
    # _NAME_TOKEN_RE (Title Case only) evita falso-positivo em substantivos
    # comuns do PT-BR ("rosa", "clara", "vera" — todos no _BR_TOP_NAMES como
    # nomes femininos mas tambem palavras genericas em minuscula).
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
