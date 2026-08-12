"""The vocabulary the corpus is built from.

Four name pools, split by whether the shield's own lists recognise the token, because that split
is where the interesting behaviour lives: a name on both lists is easy, and a name on neither is
the case that actually reaches this product. `test_pii_corpus.py` asserts each pool really is on
the side it claims -- if someone adds `Kranz` to `_BR_TOP_SURNAMES`, the corpus must say so out
loud rather than quietly getting easier to pass.

Nobody here is real. The off-list names are ordinary Brazilian given names and surnames of the
Italian, German and Polish descent that is unremarkable in the south of the country; the on-list
ones are drawn from the shield's own frequency lists.
"""

from __future__ import annotations

# --------------------------------------------------------------------------- #
# People
# --------------------------------------------------------------------------- #

# Given names the shield recognises (`_BR_TOP_NAMES`).
ON_LIST_GIVEN: tuple[str, ...] = (
    "Ana",
    "Carlos",
    "Marina",
    "Bruno",
    "Juliana",
    "Rafael",
    "Camila",
    "Eduardo",
    "Patricia",
    "Thiago",
    "Fernanda",
    "Gustavo",
    "Leticia",
    "Rodrigo",
    "Aline",
    "Felipe",
    "Beatriz",
    "Marcelo",
    "Vanessa",
    "Leonardo",
    "Priscila",
    "Andre",
    "Renata",
    "Vinicius",
    "Larissa",
    "Diego",
    "Bianca",
    "Fabio",
    "Monica",
    "Ricardo",
)

# Given names on no list the shield holds. These are the ones that matter: `_BR_TOP_NAMES` has
# 271 entries and the country has rather more.
OFF_LIST_GIVEN: tuple[str, ...] = (
    "Wanderleia",
    "Cleiton",
    "Zoraide",
    "Nivaldo",
    "Marlene",
    "Osvaldo",
    "Kleber",
    "Edson",
    "Valdir",
    "Sueli",
    "Neusa",
    "Dirceu",
    "Elizete",
    "Gilmar",
    "Ivone",
    "Jandira",
    "Laercio",
    "Marlon",
    "Odair",
    "Reinaldo",
    "Sidnei",
    "Terezinha",
    "Valquiria",
    "Waldemar",
    "Zenaide",
    "Adilson",
    "Benedito",
    "Domingos",
    "Genivaldo",
    "Joelma",
)

# Surnames the shield recognises (`_BR_TOP_SURNAMES`).
ON_LIST_SURNAME: tuple[str, ...] = (
    "Silva",
    "Santos",
    "Oliveira",
    "Souza",
    "Costa",
    "Pereira",
    "Almeida",
    "Ribeiro",
    "Carvalho",
    "Gomes",
    "Martins",
    "Rocha",
    "Barbosa",
    "Araujo",
    "Fernandes",
    "Monteiro",
    "Cardoso",
    "Teixeira",
    "Moreira",
    "Nunes",
)

# Surnames on no list. Common in Brazil, absent from a 101-entry frequency table.
OFF_LIST_SURNAME: tuple[str, ...] = (
    "Kranz",
    "Zanchetta",
    "Hoffmann",
    "Bittencourt",
    "Wenzel",
    "Kaufmann",
    "Marchetti",
    "Poletto",
    "Bergamin",
    "Gasparini",
    "Kowalski",
    "Lazzarotto",
    "Muraro",
    "Nardelli",
    "Panizzon",
    "Rossetto",
    "Sartori",
    "Uhlmann",
    "Wollinger",
    "Zambelli",
)

# --------------------------------------------------------------------------- #
# Things that are not people, and must survive
# --------------------------------------------------------------------------- #

# On `_PERSON_NAME_NEGATIVE_LIST`. These are the tokens whose adjacency used to switch the
# redaction off -- finding 5a -- so they are the products the corpus puts next to a name.
LISTED_PRODUCTS: tuple[str, ...] = (
    "SAP",
    "Protheus",
    "Salesforce",
    "Jira",
    "Oracle",
    "Datasul",
    "Fluig",
    "Sankhya",
    "Kubernetes",
    "Postgres",
)

# On no list at all: the demo tenants and their competitors from `data/synthetic/`. A shield that
# starts eating these has traded one defect for a worse one, which is the whole reason the
# false-redaction rate is measured beside the leak rate.
UNLISTED_PRODUCTS: tuple[str, ...] = (
    "TotalSys",
    "OmniBusiness",
    "PayMatch",
    "CreatorHub",
    "PostMate",
    "Northwind",
    "Reconcile",
    "Manufatura",
)

# Company names, for the "company before a person" shape.
COMPANIES: tuple[str, ...] = (
    "Acme",
    "Northwind",
    "TotalSys",
    "OmniBusiness",
)

# --------------------------------------------------------------------------- #
# The vocabulary pools
#
# Ordinary pt-BR business words that are not people. A `[[PERSON_NAME_n]]` in place of any of
# them is a false redaction.
#
# READ THIS BEFORE ADDING A WORD HERE AND CALLING IT A GUARD. An earlier version of this block
# claimed these pools "make the loosening expensive" for finding 5b. They do not, and the claim
# was checked the only way it can be: `_is_a_name_on_its_own` was forced to return True -- the
# maximum possible loosening of the rule they were supposed to price -- and **0 of 81** cases
# built from them changed. Every one passed vacuously.
#
# The reason is structural. `_is_a_name_on_its_own` is reachable only through a Pattern 1 or
# Pattern 2 match, and both need two adjacent `_TITLE_WORD` tokens. A lone token in a sentence
# never enters that path at all, so it is not guarded by the rule -- it simply never meets it.
#
# What actually prices a loosening lives in `PREPOSITIONS` and in the `fp_split_flank` cases in
# cases.py, both of which put an ordinary word where the shield can reach it. The single-token
# pools below are kept because they assert something worth asserting, and because a future change
# elsewhere could make them bite -- but they are not the price list, and
# `test_the_false_positive_pool_is_not_inert` is what stops that claim being made again.
# --------------------------------------------------------------------------- #

# On no shield list whatsoever.
WEEKDAYS: tuple[str, ...] = (
    "Segunda",
    "Terca",
    "Quarta",
    "Quinta",
    "Sexta",
    "Sabado",
    "Domingo",
)

# Also on no list -- except `Marco`, which is on `_BR_TOP_NAMES` because it is a common pt-BR
# given name as well as a month. That collision is real and is measured rather than dodged: the
# corpus keeps the month sense and records what the shield does with it.
MONTHS: tuple[str, ...] = (
    "Janeiro",
    "Fevereiro",
    "Marco",
    "Abril",
    "Maio",
    "Junho",
    "Julho",
    "Agosto",
    "Setembro",
    "Outubro",
    "Novembro",
    "Dezembro",
)

# The same words with their real accents. `_fold` strips accents before every list lookup, so
# these SHOULD behave identically to their unaccented spellings -- and the only way that is a
# fact rather than an assumption is to run both. An earlier comment asserted the equivalence for
# `Marco` while the corpus contained no accented character at all.
ACCENTED_SPELLINGS: tuple[tuple[str, str], ...] = (
    ("Março", "Marco"),
    ("Terça", "Terca"),
    ("Sábado", "Sabado"),
)

# Only these five take the `-feira` suffix in pt-BR. `Sabado-feira` is not a string any
# transcript can contain, and a corpus whose stated rationale is realism should not invent one.
FEIRA_WEEKDAYS: tuple[str, ...] = (
    "Segunda",
    "Terca",
    "Quarta",
    "Quinta",
    "Sexta",
)

# Business areas. Eleven of the twelve are on `_COMMON_PHRASE_HEADS`; only `Contabilidade` is on
# no list. Counted rather than described as "several" -- the earlier wording implied a mix and
# the real ratio is 11 to 1, which matters when judging what these cases can and cannot guard.
DEPARTMENTS: tuple[str, ...] = (
    "Financeiro",
    "Juridico",
    "Comercial",
    "Marketing",
    "Operacoes",
    "Compras",
    "Suprimentos",
    "Faturamento",
    "Contabilidade",
    "Fiscal",
    "Logistica",
    "Qualidade",
)

# The article-plus-token shape the brief names by hand. Written as (article, token) so the case
# builder can put them in a sentence without guessing agreement.
#
# `A`/`O` are single letters, so they never match `_TITLE_WORD` and never form a sequence with
# the noun behind them. That is why these pass today and why they keep passing under any
# loosening of the single-token rule -- see `PREPOSITIONS` for the shape where it goes wrong.
ARTICLE_TOKENS: tuple[tuple[str, str], ...] = (
    ("O", "Brasil"),
    ("A", "Nota"),
    ("O", "Protheus"),
    ("A", "TOTVS"),
    ("O", "RM"),
)


# --------------------------------------------------------------------------- #
# The pool that actually reaches the rule
#
# A capitalised preposition in front of an ordinary capitalised noun is TWO `_TITLE_WORD`
# tokens, which is the exact shape `_NAME_SEQUENCE_RE` trusts. `Na Sexta` is therefore claimed
# as a person today -- this is not a hypothetical about some future loosening, it is live.
#
# Swept on 2026-08-11, 14 prepositions x 28 ordinary nouns: 196 of 392 are false redactions, and
# the split is perfectly regular.
#
#     Na  No  Nas  Nos  Pela  Pelo  Em     ->  wrong with every noun
#     Da  Do  Das  Dos  De                 ->  right today -- see below, this is NOT immunity
#     A  O                                 ->  right, and structurally so: one letter never
#                                              matches `_TITLE_WORD`, so the pair is never seen
#
# The corpus carries a 7 x 7 sample rather than the full 196: enough to hold every failing
# preposition and one noun from each family, without letting a single defect dominate a rate that
# has to stay readable.
#
# The `Da`/`Do` row needed correcting and the correction is the useful part. An earlier version
# of this note filed them beside `A`/`O` as "correct", implying the same kind of safety. They are
# not the same. `Da Segunda` DOES match `_NAME_SEQUENCE_RE`, DOES reach `_trusted_span`, and is
# saved at the last step by `_is_a_name_on_its_own("Segunda")` returning False -- the very rule
# 5b has to loosen. Measured: of 35 `Da`/`Do`/`Das`/`Dos`/`De` x noun pairs, 0 are wrong today and
# **25 break** when that rule is forced open.
#
# So they are not immune, they are the cheapest price-list cases in the file: realistic, correct
# today, and load-bearing tomorrow. `A`/`O` are the only genuinely structural pass.
# --------------------------------------------------------------------------- #

PREPOSITIONS: tuple[str, ...] = (
    "Na",
    "No",
    "Nas",
    "Nos",
    "Pela",
    "Pelo",
    "Em",
)

# On `_NAME_CONNECTIVES`, because they really do occur inside pt-BR names (`Maria da Silva`).
# Correct today, and fragile in exactly the way 5b's fix has to be careful about.
NAME_CONNECTIVE_PREPOSITIONS: tuple[str, ...] = (
    "Da",
    "Do",
    "Das",
    "Dos",
    "De",
)

# One from each family the sweep covered: two weekdays, two months, three business areas --
# including `Contabilidade`, the only department on no shield list at all.
PREPOSITION_NOUNS: tuple[str, ...] = (
    "Segunda",
    "Sexta",
    "Janeiro",
    "Outubro",
    "Financeiro",
    "Qualidade",
    "Contabilidade",
)

# --------------------------------------------------------------------------- #
# The price list for finding 5b, arrived at by measurement rather than by intuition
#
# 5b is closed by making a lone Title Case token worth something when an allow-listed term has
# split it off from the rest of a name. To price that, a case has to put an ordinary word in
# exactly that position -- inside a run the shield already reached, which the split then cuts.
#
# Each was checked by forcing `_is_a_name_on_its_own` to return True and diffing the output, in
# the exact sentence frame the case builder uses. Six break; four hold on `_COMMON_PHRASE_HEADS`
# and are kept as controls, because a price list of only breakage says nothing about what a
# careful fix should preserve.
#
#     BREAKS   Central Oracle Cloud            `Central Cloud` reads as a name
#     BREAKS   Licenca Salesforce Enterprise   `Licenca Enterprise`
#     BREAKS   Servidor Postgres Homologacao   only the first half goes
#     BREAKS   Painel Jira Executivo           only the second half goes
#     BREAKS   Relatorio Datasul Gerencial     only the second half goes
#     BREAKS   Portal Sankhya Financeiro       see the paragraph below
#     holds    Modulo Protheus Fiscal, Base Postgres Producao, Ambiente Kubernetes Producao,
#              Integracao Fluig Contabil    -- all four have BOTH ends on a phrase list
#
# This list used to open with `Portal SAP Financeiro` and count it among the holds. Review found
# it could not fail: `SAP` is all-caps, so it is not a `_TITLE_WORD`, so the string contains no
# two ADJACENT title words and `_NAME_SEQUENCE_RE` never matches it. Nothing about the shield was
# holding it -- the pattern never saw it. And `Portal` is on no list at all, so the "holds on
# `_COMMON_PHRASE_HEADS`" attribution was wrong for that row specifically.
#
# That is the same defect this branch deleted 81 cases over, so it gets the same treatment rather
# than a quiet edit: the string moves to `SPLIT_FLANK_ALLCAPS` and is labelled as the structural
# control it actually is, and `Portal Sankhya Financeiro` takes its place here. Swapping the term
# for a Title Case one makes the string break, which is the proof the shape was worth keeping.
#
# `Relatorio Datasul Gerencial` HELD in an earlier sentence frame ("saiu com erro") and BREAKS in
# this one ("entrou na pauta de ontem"). The verdict depends on what follows, which is why every
# count here is stated for the frame the builder emits and was re-derived rather than carried
# over from an earlier probe.
#
# `SPLIT_FLANK` entries are (leading article, first word, allow-listed term, second word).
# --------------------------------------------------------------------------- #

SPLIT_FLANK: tuple[tuple[str, str, str, str], ...] = (
    ("A", "Central", "Oracle", "Cloud"),
    ("A", "Licenca", "Salesforce", "Enterprise"),
    ("O", "Servidor", "Postgres", "Homologacao"),
    ("O", "Painel", "Jira", "Executivo"),
    ("O", "Portal", "Sankhya", "Financeiro"),
    ("O", "Modulo", "Protheus", "Fiscal"),
    ("A", "Base", "Postgres", "Producao"),
    ("O", "Ambiente", "Kubernetes", "Producao"),
    ("A", "Integracao", "Fluig", "Contabil"),
    ("O", "Relatorio", "Datasul", "Gerencial"),
)

# An ALL-CAPS term between two Title Case words. A different shape, kept separate so it can never
# be miscounted as a price-list control again: it cannot form a Title Case sequence, so no
# loosening of the single-token rule can reach it. It asserts only that the all-caps path leaves
# the string alone.
SPLIT_FLANK_ALLCAPS: tuple[tuple[str, str, str, str], ...] = (("O", "Portal", "SAP", "Financeiro"),)


# Phrases that are roles, artefacts or ordinary business vocabulary. Every one of these is a
# false redaction if a `[[PERSON_NAME_n]]` comes back in its place.
ROLE_PHRASES: tuple[str, ...] = (
    "Customer Success",
    "Machine Learning",
    "Pull Request",
    "Nota Fiscal",
    "Lista de Campos",
    "Relatorio de Vendas",
    "Contas Medicas",
    "Gerente de Contas",
    "Pesquisa e Desenvolvimento",
    "Acme Software Solutions",
)
