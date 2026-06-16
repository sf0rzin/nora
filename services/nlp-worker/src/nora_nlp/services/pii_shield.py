"""PII Shield baseline: regex deterministico aplicado antes de qualquer chamada ao LLM.

Cobre os tipos basicos brasileiros (e-mail, telefone, CPF, CNPJ, cartao) e detecta
nomes proprios via regex heuristico + lista hardcoded de top nomes BR + negative list
de termos tecnicos/produtos/empresas. ADDRESS fica para uma fatia futura.

Sobre o determinismo: o gate DURO de PII continua sendo regex + DV/Luhn + lista
hardcoded. O NER (spaCy `pt_core_news_sm`) entra como BACKSTOP defense-in-depth
(ADR 0012, PR #3) para PERSON_NAME que a lista + regex Title-Case nao pegam (nomes
minuscula/ALL-CAPS/estrangeiros/sobrenomes isolados) E para reduzir falso-positivo
de toponimo (LOC/GPE) e org (ORG). Se o spaCy/modelo nao carregar, o shield degrada
gracioso para SO as heuristicas (ver `_load_ner` + `_ner_spans`).

A estrategia de PERSON_NAME e detalhada na docstring de `_redact_person_names`.
"""

from __future__ import annotations

import hashlib
import logging
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from ..models import PiiRedactionV1, PiiType, Redaction

logger = logging.getLogger(__name__)


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
        # ------------------------------------------------------------------- #
        # Termos de negocio EN + expressoes PT-BR (PR #3). spaCy PT classifica
        # mal varios destes (marca como PER, ou nao reconhece). Adicionamos os
        # TOKENS individuais para que `_is_negative` descarte o match inteiro.
        # REGRA: so tokens que NAO colidem com primeiro nome BR. NUNCA adicionar
        # tokens tipo "Paulo"/"Toledo" (toponimo com colisao de nome) -- esses
        # ficam por conta do LOC/GPE do NER, nao da negative list.
        # ------------------------------------------------------------------- #
        # Customer Success, Machine Learning, Data Science, Single Sign On,
        # Service Level Agreement, Pull Request, Black Friday, Go Live.
        "Customer",
        "Success",
        "Machine",
        "Learning",
        "Data",
        "Science",
        "Single",
        "Agreement",
        "Level",
        "Service",
        "Pull",
        "Request",
        "Friday",
        "Black",
        "Live",  # "Go Live" -- "Go" tambem nao e nome
        "Go",
        "Enterprise",  # "Plano Enterprise"
        "Starter",  # "Plano Starter"
        # Expressoes PT-BR: Segunda..Sexta Feira, Boa Tarde/Noite, Muito
        # Obrigado, Nota Fiscal. So tokens sem colisao de nome ("Boa","Tarde",
        # "Noite","Muito","Obrigado","Nota","Fiscal","Feira","Segunda","Sexta",
        # "Terca","Quinta"). "Bom"/"Dia" tambem. NAO adicionamos "Quarta" para
        # nao colidir? "Quarta" nao e nome BR comum; incluimos por completude.
        "Feira",
        "Segunda",
        "Terca",
        "Quarta",
        "Quinta",
        "Sexta",
        "Boa",
        "Bom",
        "Tarde",
        "Noite",
        "Dia",
        "Muito",
        "Obrigado",
        "Obrigada",
        "Nota",
        "Fiscal",
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
# NER backstop (spaCy pt_core_news_sm) -- defense-in-depth, PR #3 / ADR 0012
# --------------------------------------------------------------------------- #
#
# Modelo `pt_core_news_sm`: ~13 MB de pesos (wheel ~15-20 MB), baixado no BUILD
# do Docker (nunca em runtime). spaCy + numpy adicionam algumas centenas de MB
# ao runtime do container -- aceitavel para o ganho de recall de PERSON_NAME.
#
# Carregamento: singleton lazy cacheado no modulo. So tok2vec + ner ficam
# habilitados (parser/tagger/lemmatizer/attribute_ruler/morphologizer DESABILITADOS)
# -- acelera muito a inferencia, ja que so precisamos de `doc.ents`.
#
# DEGRADACAO GRACIOSA (NAO-NEGOCIAVEL): se spaCy nao importar (ImportError) ou o
# modelo nao carregar (OSError), `_load_ner` retorna None, loga UMA vez, e o shield
# segue SO com as heuristicas. O worker NUNCA crasha por causa do NER.

# Sentinela: distingue "ainda nao tentei carregar" (False) de "tentei e falhou"
# (None) de "carregado" (objeto nlp). Evita re-tentar o load (e re-logar) a cada
# request quando o modelo esta ausente.
_NER_UNSET: object = object()
_ner_nlp: object = _NER_UNSET
_NER_LOAD_LOGGED = False

# Labels de entidade do spaCy PT (`pt_core_news_sm`):
#   PER          -> pessoa (candidato direto a redigir como PERSON_NAME)
#   LOC/GPE/ORG  -> local/geopolitico/organizacao (spans candidatos a PROTEGER:
#                   a heuristica Title-Case nao deve redigir "Sao Paulo",
#                   "Rio de Janeiro", logradouros, "Customer Success" etc.)
#   MISC         -> ruido (ALL-CAPS, termos EN). Nao protege nem redige sozinho.
#
# PORQUE NAO E SO "PER redige / LOC-GPE-ORG protege": o modelo `sm` e ruidoso e
# rotula MUITO nome de pessoa fora-da-lista como ORG/LOC ("O Cleiton"->ORG,
# "O Sampaio"->LOC, "A Priya"->ORG). Se protegessemos esses spans cegamente, o
# nome VAZARIA (regressao dos casos 38-60). A discriminacao confiavel observada:
#   - toponimo/termo real (proteger): MULTI-palavra ("Sao Paulo") OU single-token
#     precedido por preposicao/nao-artigo ("Pelo Toledo", "Nota Fiscal").
#   - pessoa (redigir): single-token Title-Case precedido por ARTIGO/pronome
#     PT-BR ("O/A/Os/As <Nome>") ou por "com"/virgula -- padrao "o <Fulano>".
# Por isso classificamos por (label + arity + token anterior), nao so por label.
_NER_PERSON_LABELS = frozenset({"PER"})
_NER_PROTECTED_LABELS = frozenset({"LOC", "GPE", "ORG"})

# Artigos/pronomes PT-BR que, imediatamente antes de um proper-noun single-token,
# sinalizam referencia a PESSOA ("o Cleiton", "a Wanderleia"). `_fold` ja remove
# acento/caixa. "com" e cobertos a parte (preposicao de companhia).
_NER_PERSON_LEADING = frozenset({"o", "a", "os", "as", "com"})

# Heads de TOPONIMO/LOGRADOURO: primeira palavra (foldada) de um span LOC/GPE
# multi-token que o caracteriza como LUGAR e nao pessoa. CRITICO p/ "Sao Paulo"
# (o `sm` rotula LOC, mas "Paulo" esta em `_BR_TOP_NAMES` -> sem este gate o
# Padrao 3 redigiria "Paulo" como pessoa). Distingue "Sao Paulo"/"Belo Horizonte"
# (lugar -> protege) de "Marina Alves"/"Ana Paula" (pessoa -> NAO protege, mesmo
# que o `sm` rotule LOC). So protegemos spans LOC/GPE multi-token cuja cabeca
# esta aqui OU que nao tenham NENHUM token na lista de nomes (toponimo "puro").
_TOPONYM_HEADS = frozenset(
    _fold(h)
    for h in (
        # Cabecas geograficas
        "Sao",
        "Santa",
        "Santo",
        "Rio",
        "Belo",
        "Porto",
        "Nova",
        "Novo",
        "Serra",
        "Campo",
        "Campos",
        "Vila",
        "Monte",
        "Lago",
        "Lagoa",
        "Praia",
        "Ponta",
        "Foz",
        "Oriente",
        "Norte",
        "Sul",
        "Leste",
        "Oeste",
        # Logradouros
        "Rua",
        "Av",
        "Avenida",
        "Travessa",
        "Alameda",
        "Praca",
        "Largo",
        "Rodovia",
        "Estrada",
        "Viela",
        "Ladeira",
    )
)


def _load_ner() -> object | None:
    """Carrega (uma vez) o pipeline spaCy de NER pt-BR; None se indisponivel.

    Singleton lazy: cacheia o resultado no modulo (`_ner_nlp`). Em ImportError
    (spaCy ausente) ou OSError (modelo `pt_core_news_sm` nao instalado), loga UMA
    vez via WARNING e cacheia None -- o shield degrada para so as heuristicas e o
    worker continua funcionando. Qualquer outra excecao inesperada tambem cai no
    caminho gracioso (defense-in-depth: NER nunca derruba o shield).
    """
    global _ner_nlp, _NER_LOAD_LOGGED
    if _ner_nlp is not _NER_UNSET:
        return _ner_nlp  # ja resolvido (objeto nlp ou None)

    try:
        import spacy  # import tardio: so paga o custo se o NER for usado

        _ner_nlp = spacy.load(
            "pt_core_news_sm",
            # So tok2vec + ner. O resto e peso morto para deteccao de entidade.
            disable=["parser", "tagger", "lemmatizer", "attribute_ruler", "morphologizer"],
        )
    except Exception as exc:  # gracioso de proposito (ADR 0012): NER nunca derruba o shield
        if not _NER_LOAD_LOGGED:
            logger.warning(
                "PII Shield: NER backstop indisponivel (%s: %s); seguindo so com "
                "heuristicas deterministicas.",
                type(exc).__name__,
                exc,
            )
            _NER_LOAD_LOGGED = True
        _ner_nlp = None
    return _ner_nlp


def _leading_token(text: str, start: int) -> str | None:
    """Ultimo token alfabetico ANTES da posicao `start` (foldado), ou None.

    Usado para o sinal "artigo + proper-noun = pessoa": olha a palavra imediata
    a esquerda do span (ignorando espacos/pontuacao simples) e devolve `_fold`.
    """
    prefix = text[:start]
    matches = list(_WORD_RE.finditer(prefix))
    if not matches:
        return None
    # So conta como "imediatamente antes" se houver apenas espacos/pontuacao
    # leve entre o token anterior e o span (evita casar palavra distante).
    gap = prefix[matches[-1].end() :]
    if re.search(r"[A-Za-zÁÉÍÓÚÂÊÔÀÃÕÇáéíóúâêôàãõç0-9]", gap):
        return None
    return _fold(matches[-1].group(0))


def _is_toponym_span(value: str) -> bool:
    """True se um span LOC/GPE multi-token deve PROTEGER (e lugar, nao pessoa).

    Protege quando a CABECA do span e um head de toponimo/logradouro ("Sao",
    "Belo", "Rio", "Rua"...) OU quando NENHUM token do span esta em `_BR_TOP_NAMES`
    (toponimo "puro", sem colisao de nome). NAO protege "Marina Alves"/"Ana Paula"
    (cabeca e nome, ha token-nome) -- assim o `sm` rotulando essas como LOC nao
    deixa o nome vazar (o contrato deterministico vence).
    """
    tokens = _tokenize(value)
    if not tokens:
        return False
    head = _fold(tokens[0])
    if head in _TOPONYM_HEADS:
        return True
    return not any(_fold(tok) in _BR_TOP_NAMES for tok in tokens)


def _ner_spans(text: str) -> tuple[list[tuple[int, int, str]], list[tuple[int, int]]]:
    """Roda o NER sobre `text` e retorna (candidatos_pessoa, spans_protegidos).

    - candidatos_pessoa: `(start, end, value)` a redigir como PERSON_NAME. Inclui:
        (a) todas as entidades `PER`;
        (b) entidades single-token LOC/GPE/ORG/MISC Title-Case precedidas por
            artigo/pronome PT-BR ("o Cleiton", "a Wanderleia", "com Klaus") --
            o `sm` mislabela muito nome fora-da-lista como ORG/LOC; este resgate
            recupera o recall sem deixar "Sao Paulo" virar pessoa (multi-token).
    - spans_protegidos: `(start, end)` de LOC/GPE que sao TOPONIMO/LOGRADOURO real
        (ver `_is_toponym_span`) -- ranges onde a heuristica Title-Case nao deve
        redigir ("Sao Paulo", "Rio de Janeiro", "Belo Horizonte", logradouros).
        ORG NAO entra como protegido (termos de negocio EN ja estao na negative
        list, e ORG e onde o `sm` mais mislabela pessoa).

    O contrato deterministico vence: spans que contem nome conhecido ou nao sao
    toponimo nao protegem nada -- recall de PERSON_NAME > precisao (vazamento e
    irreversivel; over-redacao so piora UX).

    Se o NER estiver indisponivel, retorna `([], [])` -- o caller cai 100% nas
    heuristicas. Qualquer falha de inferencia tambem degrada para `([], [])`.
    """
    nlp = _load_ner()
    if nlp is None:
        return [], []
    try:
        doc = nlp(text)
    except Exception as exc:  # inferencia nunca derruba o shield (defense-in-depth)
        logger.warning("PII Shield: falha na inferencia NER (%s); ignorando.", type(exc).__name__)
        return [], []

    persons: list[tuple[int, int, str]] = []
    protected: list[tuple[int, int]] = []
    for ent in doc.ents:
        span = (ent.start_char, ent.end_char)
        if ent.label_ in _NER_PERSON_LABELS:
            persons.append((ent.start_char, ent.end_char, ent.text))
            continue

        if ent.label_ not in _NER_PROTECTED_LABELS:
            continue  # MISC e ruido: nem redige nem protege

        # Resgate de pessoa mislabelada como ORG/LOC: single-token, Title-Case,
        # precedido de artigo/pronome PT-BR. Distingue "o Cleiton" (pessoa) de
        # "Pelo Toledo"/"Nota Fiscal" (preposicao/substantivo antes) e de
        # "Sao Paulo" (multi-token -> nunca resgatado, cai no toponym check).
        single_token = " " not in ent.text and _NAME_TOKEN_RE.fullmatch(ent.text) is not None
        if single_token:
            leading = _leading_token(text, ent.start_char)
            if leading in _NER_PERSON_LEADING:
                persons.append((ent.start_char, ent.end_char, ent.text))
                continue

        # Protege APENAS LOC/GPE que e toponimo/logradouro real. ORG nunca protege.
        if ent.label_ in ("LOC", "GPE") and _is_toponym_span(ent.text):
            protected.append(span)

    return persons, protected


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

    Ordem das passadas (cada uma pula ranges ja cobertos / protegidos):

    0. NER (spaCy): entidades `PER` viram claims de PERSON_NAME (com prioridade,
       claimadas primeiro). Entidades `LOC`/`GPE`/`ORG` viram spans PROTEGIDOS --
       as heuristicas Title-Case NAO redigem dentro deles (assim "Sao Paulo",
       "Rio de Janeiro", logradouros e orgs param de virar [[PERSON_NAME]]).
       Se o NER estiver indisponivel, esta passada e no-op e tudo cai nas
       heuristicas (degradacao graciosa -- ADR 0012).
    1. Prefixo + Title Case (`Dr. Carlos Silva`).
    2. 2-4 palavras Title Case consecutivas (`Marina Alves`).
    3. Primeiro nome BR isolado contra a lista hardcoded (`Lucas`, `Marina`).

    A negative list filtra produtos/empresas/tecnologias antes de gerar o
    placeholder -- aplicada TAMBEM sobre as entidades PER do NER (spaCy as vezes
    marca produto/termo de negocio como PER). Cada ocorrencia recebe um numero
    novo (sem dedup -- decisao explicita do escopo).

    O determinismo do contrato duro (regex + DV/Luhn + lista) permanece: o NER
    so ADICIONA recall de PERSON_NAME e SUPRIME falso-positivo de toponimo/org.
    """
    person_matches: list[_Match] = []
    covered: list[tuple[int, int]] = []  # ranges (start, end) ja consumidos

    # Passada 0: NER. PER -> candidatos; LOC/GPE/ORG -> protegidos.
    ner_persons, protected_spans = _ner_spans(text)

    def _is_protected(start: int, end: int) -> bool:
        """True se [start,end) sobrepoe um span LOC/GPE/ORG (toponimo/org do NER)."""
        return any(not (end <= ps or start >= pe) for ps, pe in protected_spans)

    def _is_covered(start: int, end: int) -> bool:
        return any(not (end <= cs or start >= ce) for cs, ce in covered)

    def _claim(start: int, end: int, value: str) -> None:
        person_matches.append(_Match(type=PiiType.PERSON_NAME, start=start, end=end, value=value))
        covered.append((start, end))

    # Passada 0: entidades PER do NER (prioridade -- claimadas antes das heuristicas).
    # Aplica negative list + checagem de que o span ainda contem texto real (o
    # texto ja foi parcialmente redigido; uma entidade que caiu sobre um
    # placeholder e ignorada para nao redigir o placeholder de novo).
    for start, end, value in ner_persons:
        if _is_covered(start, end):
            continue
        if "[[" in value:  # entidade sobre placeholder ja redigido -- ignora
            continue
        if _is_negative(value):
            continue
        _claim(start, end, value)

    # Padrao 1: prefixo. Pula matches dentro de span protegido (LOC/GPE/ORG).
    for m in _NAME_PREFIX_RE.finditer(text):
        if _is_covered(m.start(), m.end()) or _is_protected(m.start(), m.end()):
            continue
        if _is_negative(m.group(0)):
            continue
        _claim(m.start(), m.end(), m.group(0))

    # Padrao 2: sequencia Title Case (2-4 palavras). Pula spans protegidos --
    # e aqui que "Sao Paulo"/"Rio de Janeiro"/logradouros deixam de virar nome.
    for m in _NAME_SEQUENCE_RE.finditer(text):
        if _is_covered(m.start(), m.end()) or _is_protected(m.start(), m.end()):
            continue
        if _is_negative(m.group(0)):
            continue
        _claim(m.start(), m.end(), m.group(0))

    # Padrao 3: primeiro nome BR isolado (Title Case, contra lista hardcoded).
    # _NAME_TOKEN_RE (Title Case only) evita falso-positivo em substantivos
    # comuns do PT-BR ("rosa", "clara", "vera" — todos no _BR_TOP_NAMES como
    # nomes femininos mas tambem palavras genericas em minuscula).
    for m in _NAME_TOKEN_RE.finditer(text):
        if _is_covered(m.start(), m.end()) or _is_protected(m.start(), m.end()):
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
