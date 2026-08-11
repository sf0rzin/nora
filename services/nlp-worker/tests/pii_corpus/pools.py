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
