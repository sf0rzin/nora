import re
import time
import unicodedata

import pytest

from nora_nlp.models import PiiType
from nora_nlp.services import pii_shield


def test_redacts_email_and_phone():
    text = "Fala com a Marina marina@cliente.com.br ou no (11) 98888-7777, ok?"
    result = pii_shield.redact(text)
    assert "marina@cliente.com.br" not in result.redacted_text
    assert "98888-7777" not in result.redacted_text
    types = {r.type for r in result.redactions}
    assert PiiType.EMAIL in types
    assert PiiType.PHONE in types


def test_redacts_cpf_and_cnpj():
    text = "CPF 123.456.789-00 e CNPJ 12.345.678/0001-90."
    result = pii_shield.redact(text)
    assert "123.456.789-00" not in result.redacted_text
    assert "12.345.678/0001-90" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)
    assert any(r.type == PiiType.CNPJ for r in result.redactions)


def test_no_pii_returns_original():
    text = "Reuniao alinhou proxima entrega para sexta."
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert result.redactions == []


def test_placeholder_format():
    text = "a@b.com e c@d.com"
    result = pii_shield.redact(text)
    assert "[[EMAIL_1]]" in result.redacted_text
    assert "[[EMAIL_2]]" in result.redacted_text


# --------------------------------------------------------------------------- #
# PERSON_NAME -- positives
# --------------------------------------------------------------------------- #


def test_redacts_isolated_br_first_name():
    """Pattern 3: BR first name alone against a hardcoded list."""
    text = "O Lucas confirmou a proposta"
    result = pii_shield.redact(text)
    assert result.redacted_text == "O [[PERSON_NAME_1]] confirmou a proposta"
    assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_redacts_accented_first_name_regression():
    """Regression (jun/2026, real production leak): ACCENTED names from the list
    leaked because `_BR_TOP_NAMES` is written WITHOUT accents and the comparison
    was accent-sensitive `casefold()` -- 'Patrícia' ('patrícia') never matched
    'Patricia' ('patricia'). The accent-fold (NFKD) fixes the whole class."""
    for name in ("Patrícia", "Antônio", "André", "João", "Mário", "Mônica", "Vitória", "César"):
        text = f"A {name} ficou de enviar o contrato"
        result = pii_shield.redact(text)
        assert name not in result.redacted_text, f"VAZOU nome acentuado: {name}"
        assert any(r.type == PiiType.PERSON_NAME for r in result.redactions), (
            f"{name} nao gerou redacao PERSON_NAME"
        )


def test_accent_fold_does_not_break_negative_list():
    """Negative list terms keep NOT being redacted after the accent-fold."""
    text = "Vamos rodar no Azure com Python e Spring, integrando com Salesforce"
    result = pii_shield.redact(text)
    for term in ("Azure", "Python", "Spring", "Salesforce"):
        assert term in result.redacted_text, f"{term} foi redigido por engano"


def test_redacts_name_surname_sequence():
    """Pattern 2: 2 consecutive Title Case words."""
    text = "Marina Alves do RH"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[PERSON_NAME_1]] do RH"
    person = [r for r in result.redactions if r.type == PiiType.PERSON_NAME]
    assert len(person) == 1


def test_redacts_name_with_prefix():
    """Pattern 1: honorific + Name [Surname]."""
    text = "Falamos com Dr. Carlos Silva"
    result = pii_shield.redact(text)
    assert result.redacted_text == "Falamos com [[PERSON_NAME_1]]"
    assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_redacts_sra_prefix():
    text = "A Sra. Ana Paula vai apresentar"
    result = pii_shield.redact(text)
    assert "[[PERSON_NAME_1]]" in result.redacted_text
    assert "Sra. Ana Paula" not in result.redacted_text


def test_multiple_distinct_people_get_distinct_numbers():
    """Each detected name gets a new number (no dedup)."""
    text = "Marina ligou pro Pedro sobre o RM"
    result = pii_shield.redact(text)
    assert result.redacted_text == ("[[PERSON_NAME_1]] ligou pro [[PERSON_NAME_2]] sobre o RM")
    person = [r for r in result.redactions if r.type == PiiType.PERSON_NAME]
    assert len(person) == 2


def test_same_person_repeated_gets_new_number():
    """Explicit scope decision: each occurrence gets a new number."""
    text = "Lucas chegou, Lucas falou, Lucas saiu"
    result = pii_shield.redact(text)
    assert "[[PERSON_NAME_1]]" in result.redacted_text
    assert "[[PERSON_NAME_2]]" in result.redacted_text
    assert "[[PERSON_NAME_3]]" in result.redacted_text


# --------------------------------------------------------------------------- #
# PERSON_NAME -- negatives (negative list and non-names)
# --------------------------------------------------------------------------- #


def test_negative_list_blocks_product_pair():
    """'TOTVS Protheus' must not become PERSON_NAME."""
    text = "Vamos usar TOTVS Protheus"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_negative_list_blocks_company_and_product():
    """'A Acme contratou NORA' -- both on the negative list, text unchanged."""
    text = "A Acme contratou NORA"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_negative_token_does_not_shield_the_name_next_to_it():
    """A product glued to the name cannot switch off the name redaction.

    Regression: `_is_negative` was all-or-nothing -- a single negative list token
    discarded the whole match of the Title Case sequence. Since the regex is greedy,
    writing "Ana Souza Protheus" made "Ana Souza" come out in the clear.
    """
    result = pii_shield.redact("Reuniao com Ana Souza Protheus na terca")
    assert "Ana Souza" not in result.redacted_text
    assert "Protheus" in result.redacted_text
    assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_negative_token_between_two_names_keeps_the_longest_run():
    """With the offender in the middle, the longest clean contiguous run remains."""
    result = pii_shield.redact("Acme Protheus Ana Souza fechou o contrato")
    assert "Ana Souza" not in result.redacted_text
    assert "Acme" in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_all_negative_tokens_still_redact_nothing():
    """Counter-proof of the trimming: with no clean token, nothing is redacted."""
    text = "Comparamos Protheus Datasul lado a lado"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_a_negative_token_between_two_names_redacts_both():
    """Regression: only the first clean run survived and the second name came out clear."""
    result = pii_shield.redact("Ana Souza Protheus Carlos Silva")
    assert "Ana Souza" not in result.redacted_text
    assert "Carlos Silva" not in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_a_trimmed_prefix_claim_does_not_swallow_a_clean_sequence():
    """Regression: the trimmed span from Pattern 1 blocked a clean Pattern 2 match."""
    result = pii_shield.redact("Ribeiro Alves Dr. Ana Protheus")
    assert "Ribeiro Alves" not in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_a_trim_never_splices_a_placeholder_into_a_surname():
    """Regression: the cut fell at the match end, gluing the placeholder to the name tail.

    This asserted `"Núñez" in redacted_text` for a while -- refusing the cut left the surname
    in the clear, and not-splicing was bought by not-redacting. Both halves are available now
    that the letter classes cover Latin-1: no splice AND no leak.
    """
    result = pii_shield.redact("Sr. Protheus Carlos Núñez")
    assert "]]ñez" not in result.redacted_text
    assert "Núñez" not in result.redacted_text
    assert "Carlos" not in result.redacted_text
    assert "Protheus" in result.redacted_text


@pytest.mark.parametrize(
    "text, surname",
    [
        ("Eng. Schürmann revisou o escopo.", "Schürmann"),
        ("Dr. Núñez aprovou.", "Núñez"),
        ("A Sra. Müller assinou.", "Müller"),
        ("Cap. Sjöberg confirmou.", "Sjöberg"),
    ],
)
def test_a_surname_outside_the_pt_br_alphabet_is_redacted_whole(text, surname):
    """Regression: the letter classes only knew the PT-BR accents.

    `[a-záéíóúâêôàãõç]+` stopped AT the foreign letter, so "Eng. Schürmann" matched as
    "Eng. Sch" and the placeholder went out spliced into the middle of the surname --
    "[[PERSON_NAME_1]]ürmann", which corrupts the text the model reads and leaks the tail in
    the same stroke. Brazil is full of German, Spanish and Nordic surnames.
    """
    result = pii_shield.redact(text)
    assert surname not in result.redacted_text
    # The anti-splice assertion has to look at the character AFTER the placeholder, not at a
    # guessed tail. It was written `"]]" + surname[3:]`, which only happens to be the spliced
    # tail for "Schurmann" (Sch|urmann): "Nunez"[3:] is "ez" but the splice is "nez", and
    # "Sjoberg"[3:] is "berg" but the splice is "oberg". Two of the four cases passed against
    # the parent commit while the placeholder WAS spliced and the tail WAS leaking.
    assert not re.search(r"\]\]\w", result.redacted_text), result.redacted_text
    assert "[[PERSON_NAME_1]]" in result.redacted_text


@pytest.mark.parametrize(
    "text",
    [
        "Precisamos da Lista de Campos do Protheus.",
        "Falta o Relatorio de Vendas do Prado.",
        "A conta e no Banco do Brasil.",
        "Abrimos a Ordem de Servico da Cruz Azul.",
    ],
)
def test_an_ordinary_genitive_phrase_is_not_a_person(text):
    """Regression: the tail signal fired on business vocabulary.

    A good third of `_BR_TOP_SURNAMES` doubles as an ordinary Portuguese noun -- Campos,
    Cruz, Prado, Neves, Barros -- so any Title Case phrase ending in one was read as a
    person. It mutilated the text AND filed the hash of a phrase that is nobody. The tail
    signal now refuses a `<noun> da <noun>` chain that no given name opens.
    """
    result = pii_shield.redact(text)
    assert "PERSON_NAME" not in result.redacted_text
    assert result.redacted_text == text


@pytest.mark.parametrize(
    "given, surname",
    [
        ("Edson", "Costa"),
        ("Wanderleia", "Martins"),
        ("Osvaldo", "Pinheiro"),
        ("Genoveva", "Silveira"),
        ("Anastacio", "Magalhães"),
        ("Teodolinda", "Brandão"),
        ("Jocimar", "Barros"),
        ("Adilson", "Correa"),
    ],
)
def test_the_tail_list_covers_the_most_common_brazilian_surnames(given, surname):
    """Regression: the list was missing names from the IBGE top 10, Costa and Martins among
    them, so a full name built on one of them had no tail signal and left in the clear.

    Every case carries "Protheus". Without it the phrase is a clean 2-token Title Case
    sequence, which `_spans_without_negatives` trusts whole WITHOUT ever consulting
    `_BR_TOP_SURNAMES` -- so the earlier version of this test passed on Pattern 2 and only one
    of its six cases actually exercised the list. Deleting Martins from the list left it
    green. The negative term is what forces the qualification path where the tail is read.
    """
    result = pii_shield.redact(f"{given} {surname} Protheus")
    assert given not in result.redacted_text
    assert surname not in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_the_surname_list_is_what_carries_that_test(monkeypatch):
    """Counter-proof for the test above: empty the list and every case must fail.

    Without this, a future edit that made the qualification path stop consulting
    `_BR_TOP_SURNAMES` would go unnoticed -- the assertions would keep passing for some other
    reason, which is exactly how the previous version of the test broke.
    """
    monkeypatch.setattr(pii_shield, "_BR_TOP_SURNAMES", frozenset())
    assert pii_shield.redact("Edson Costa Protheus").redacted_text == "Edson Costa Protheus"


def test_a_job_title_does_not_cancel_the_given_name_after_it():
    """A role has nobody in it; "Diretor Carlos da Silva" is a person.

    Reading only token 0 gets one of the two wrong whichever way it is decided -- the head
    signal has to look past a leading job title, and only then at a known given name.
    """
    person = pii_shield.redact("O Diretor Carlos da Silva decidiu.")
    assert "Carlos" not in person.redacted_text
    assert "Silva" not in person.redacted_text

    role = pii_shield.redact("O Gerente de Contas Oracle respondeu.")
    assert role.redacted_text == "O Gerente de Contas Oracle respondeu."


def test_a_leftover_fragment_is_never_claimed_with_its_separating_space(monkeypatch):
    """Regression: `_claim_free_parts` cut at the covered neighbour's edge and claimed the
    gap as-is, filing `_hash(" Silva")` -- the hash of something nobody wrote, which is what
    makes the redaction record auditable in the first place.

    The hash is what the record carries, so the hash is what is inspected: every value that
    reaches it has to be a name standing on its own, with no edge whitespace.
    """
    hashed: list[str] = []
    real_hash = pii_shield._hash
    monkeypatch.setattr(pii_shield, "_hash", lambda v: (hashed.append(v), real_hash(v))[1])

    for probe in (
        "Ribeiro Alves Dr. Ana Protheus",
        "Ana Souza Protheus Carlos Silva e Dr. Nogueira de Protheus",
        "Sr. Jose Protheus da Silva",
        "Dr. Carlos Protheus Silva Protheus Marina Alves",
    ):
        pii_shield._redact_person_names(probe, {pii_shield.PiiType.PERSON_NAME: 0})

    assert hashed, "the probes must claim something, otherwise this asserts nothing"
    for value in hashed:
        assert value == value.strip(), f"{value!r} carries an edge space"


def test_an_unqualified_leftover_fragment_is_not_claimed_as_a_person(monkeypatch):
    """The fragment gate is load-bearing, shown by removing it.

    This started as an assertion that every hashed value satisfies `_is_a_name_on_its_own`,
    which is true by construction -- `_qualify_and_claim` only claims what passes that very
    call, so the assertion re-applied the production gate to the production output and could
    never fail. Deleting the gate left the whole suite green.

    Mutating the gate off is what binds. "Contrato" is an ordinary noun; without the gate the
    fragment left over between the start of the Pattern 2 match and the span Pattern 1 already
    claimed is taken for a person and its hash filed.
    """
    probe = "Zanchetta Dr. Ana chegou."
    assert pii_shield.redact(probe).redacted_text == "Zanchetta [[PERSON_NAME_1]] chegou."

    monkeypatch.setattr(pii_shield, "_is_a_name_on_its_own", lambda value: True)
    assert (
        pii_shield.redact(probe).redacted_text == "[[PERSON_NAME_1]] [[PERSON_NAME_2]] chegou."
    ), "the gate must be what keeps the fragment out; if this matches the line above it is inert"


@pytest.mark.parametrize(
    "text, leaked",
    [
        ("Osvaldo Pinheiro e Marina Alves ficaram de responder.", ["Osvaldo", "Pinheiro", "Alves"]),
        ("Presentes: Edson Costa e Ana Souza.", ["Edson", "Costa", "Souza"]),
        ("Kleber Almeida e Patricia Viana vao fechar o contrato.", ["Kleber", "Almeida", "Viana"]),
    ],
)
def test_the_conjunction_e_joins_two_people_and_is_not_a_genitive(text, leaked):
    """Regression introduced by the genitive gate itself.

    `e` was in `_NAME_CONNECTIVES` alongside the genitive prepositions, so the gate read
    "Osvaldo Pinheiro e Marina Alves" as one noun phrase with no given name at its head and
    refused the whole run -- leaking both people, with only the bare token "Marina" caught by
    Pattern 3. The outcome even flipped on word order: put the listed given name first and the
    same two people were protected. `e` is a coordinating conjunction; in this corpus its job
    is to join two DIFFERENT people.
    """
    result = pii_shield.redact(text)
    for token in leaked:
        assert token not in result.redacted_text, result.redacted_text


def test_the_order_of_two_names_does_not_decide_whether_they_are_redacted():
    # Two people, two placeholders, two distinct hashes -- the run is split at the conjunction
    # and each side qualified on its own, so neither depends on the other being recognised.
    a = pii_shield.redact("Osvaldo Pinheiro e Marina Alves ficaram de responder.").redacted_text
    b = pii_shield.redact("Marina Alves e Osvaldo Pinheiro ficaram de responder.").redacted_text
    assert a == b == "[[PERSON_NAME_1]] e [[PERSON_NAME_2]] ficaram de responder."


@pytest.mark.parametrize(
    "text, name",
    [
        ("Falei com Nivaldo da Silva ontem.", "Nivaldo da Silva"),
        ("Zoraide dos Santos assumiu.", "Zoraide dos Santos"),
        ("Kleber de Souza respondeu.", "Kleber de Souza"),
        ("Genivaldo de Oliveira revisou.", "Genivaldo de Oliveira"),
        ("Marlene da Costa aprovou.", "Marlene da Costa"),
        ("Osvaldo do Nascimento ligou.", "Osvaldo do Nascimento"),
        ("Iracema de Lima confirmou.", "Iracema de Lima"),
        ("Creuza dos Reis enviou.", "Creuza dos Reis"),
        ("Valdomiro da Rocha comentou.", "Valdomiro da Rocha"),
    ],
)
def test_the_particle_that_marks_a_brazilian_full_name_does_not_switch_the_shield_off(text, name):
    """Regression: the genitive gate leaked the most common full-name shape in the country.

    The rule was "unknown head + a preposition => not a person", and "Nivaldo da Silva",
    "Zoraide dos Santos", "Marlene da Costa" are exactly that shape. Twelve of sixteen
    realistic names went to the LLM in the clear -- the tail signal was there (Silva, Santos,
    Costa are all on the surname list) and was being discarded. The head now has to be
    RECOGNISED as ordinary vocabulary before a phrase is refused; unknown means person.
    """
    result = pii_shield.redact(text)
    assert name not in result.redacted_text, result.redacted_text
    for token in name.split():
        if pii_shield._fold(token) in pii_shield._NAME_CONNECTIVES:
            continue
        assert token not in result.redacted_text, result.redacted_text


@pytest.mark.parametrize(
    "text, expected",
    [
        ("CARLOS SILVA APROVOU O ESCOPO.", "[[PERSON_NAME_1]] APROVOU O ESCOPO."),
        (
            "FICOU COM EDSON SILVA O ACOMPANHAMENTO.",
            "FICOU COM [[PERSON_NAME_1]] O ACOMPANHAMENTO.",
        ),
        (
            "ATA: PRESENTES CARLOS SILVA E MAIS DOIS.",
            "ATA: PRESENTES [[PERSON_NAME_1]] E MAIS DOIS.",
        ),
        ("ANA SILVA-COSTA aprovou.", "[[PERSON_NAME_1]] aprovou."),
    ],
)
def test_an_all_caps_name_does_not_swallow_the_words_around_it(text, expected):
    """Regression: Pattern 4 claimed the whole greedy run.

    "CARLOS SILVA APROVOU O ESCOPO" lost the verb -- the decision itself disappeared from the
    text the model reads -- and filed `_hash("CARLOS SILVA APROVOU")`, which is nobody. Only
    the stretch that opens on a recognised token and closes on a recognised one is the name.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text",
    [
        "ACOMPANHAMENTO DE CAMPOS.",
        "LISTA DE CAMPOS foi revisada.",
        "PRECISAMOS DA LISTA DE CAMPOS DO PROTHEUS.",
        "FALTA O RELATORIO DE VENDAS DO PRADO.",
        "PRAZO DE MUITOS DIAS.",
        "Bairro SANTA CRUZ na proposta.",
    ],
)
def test_an_upper_cased_business_phrase_is_not_a_person(text):
    """Upper-casing an ordinary phrase must not change the answer.

    Pattern 4 admitted head-OR-tail, and a third of the surname list doubles as an ordinary
    Portuguese noun, so every heading ending in Campos, Cruz, Dias or Prado became a person --
    including the upper-cased twins of the phrases the suite already pins as "not a person".
    """
    assert pii_shield.redact(text).redacted_text == text


@pytest.mark.parametrize(
    "text, expected",
    [
        ("O Protheus da Nota Fiscal quebrou.", "O Protheus da Nota Fiscal quebrou."),
        ("O Jira do Time Comercial esta parado.", "O Jira do Time Comercial esta parado."),
        ("A Sprint do Modulo Fiscal atrasou.", "A Sprint do Modulo Fiscal atrasou."),
        ("O Backlog do Time Novo cresceu.", "O Backlog do Time Novo cresceu."),
    ],
)
def test_a_product_owning_a_thing_is_not_a_product_owning_a_person(text, expected):
    """Counter-proof for the possessive exemption: not everything a product owns is a person."""
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text",
    [
        "Pesquisa e Desenvolvimento vai avaliar.",
        "Custos e Despesas subiram.",
        "Governanca e Risco pediu o relatorio.",
        "Compras e Suprimentos ainda nao respondeu.",
    ],
)
def test_two_departments_joined_by_e_are_not_a_person(text):
    """The other side of splitting on `e`: each half has to stand as a name on its own, and a
    single ordinary word does not."""
    assert pii_shield.redact(text).redacted_text == text


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Contato Carlos Silva-jr.", "Contato [[PERSON_NAME_1]]-jr."),
        ("Responsavel Marina Alves-financeiro.", "Responsavel [[PERSON_NAME_1]]-financeiro."),
    ],
)
def test_a_lowercase_tail_after_a_hyphen_is_a_suffix_not_half_a_surname(text, expected):
    """Regression: refusing the cut before ANY letter leaked the surname.

    The hyphen clause was added so "Silva-Costa" would not be cut in half. Written as
    `.isalpha()` it also refused "Silva-jr", and refusing means the whole span is dropped:
    Pattern 3 then caught the given name alone and "Silva" went out in the clear where it had
    been redacted before. Only a CAPITAL after the hyphen is the other half of a compound
    surname -- which is what `_TITLE_WORD`'s own hyphen branch already says.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Ana-Paula ficou de responder.", "[[PERSON_NAME_1]] ficou de responder."),
        ("Maria-Fernanda abriu o chamado.", "[[PERSON_NAME_1]] abriu o chamado."),
        ("A Ana-Maria confirmou.", "A [[PERSON_NAME_1]] confirmou."),
        ("Maria-do-Carmo assinou.", "[[PERSON_NAME_1]] assinou."),
        ("Ana-claudia confirmou.", "[[PERSON_NAME_1]] confirmou."),
    ],
)
def test_a_hyphenated_given_name_is_redacted_whole(text, expected):
    """Regression: the hyphen branch of `_TITLE_WORD` made Pattern 3 blind.

    "Ana-Paula" became ONE token, and "ana-paula" is on no list of single given names, so a
    name that used to be redacted as two tokens leaked whole. Where the compound continues in
    lowercase ("Maria-do-Carmo") the token stopped at "Maria" instead and the placeholder went
    out spliced. Pattern 3 now widens to the whole compound and reads its parts.
    """
    assert pii_shield.redact(text).redacted_text == expected


def test_a_name_beyond_the_fifth_token_is_not_left_behind():
    """Regression: the sequence cap was five tokens.

    Product names padding the middle reach it easily. In this input the match stopped at
    "Marina" and "Alves" fell outside every pattern -- Pattern 3 only knows given names -- so
    the surname went to the LLM in the clear.
    """
    result = pii_shield.redact("Dr. Carlos Protheus Silva Protheus Marina Alves")
    for name in ("Carlos", "Silva", "Marina", "Alves"):
        assert name not in result.redacted_text, result.redacted_text
    assert result.redacted_text.count("Protheus") == 2


@pytest.mark.parametrize(
    "text",
    [
        "Revisar o RM das Contas Medicas.",
        "O SAP da Nota Fiscal Eletronica.",
        "Segue a Nota Fiscal Eletronica.",
    ],
)
def test_the_fast_path_does_not_trust_a_phrase_that_opens_on_ordinary_vocabulary(text):
    """A two-word Title Case shape is not enough when the first word is plainly not a name.

    "Contas Medicas" and "Nota Fiscal Eletronica" were claimed as people, with the hash of the
    phrase filed, purely because the regex fits. The tail is the escape hatch: if it IS a
    listed surname the phrase is still taken, so "Conta Silva" is unaffected.
    """
    assert pii_shield.redact(text).redacted_text == text


@pytest.mark.parametrize(
    "text, name",
    [
        ("Responsavel Bruno Zanchetta assumiu.", ["Bruno", "Zanchetta"]),
        ("Cliente Wanderleia Kranz cancelou.", ["Wanderleia", "Kranz"]),
        ("Contato Kleber Zanchetta ligou.", ["Kleber", "Zanchetta"]),
        ("Autor Edson Wanderlei revisou.", ["Edson", "Wanderlei"]),
        ("Contrato de Marlene da Costa assinado.", ["Marlene", "Costa"]),
        ("Proposta de Nivaldo da Silva enviada.", ["Nivaldo", "Silva"]),
    ],
)
def test_an_ordinary_word_in_front_of_a_name_does_not_switch_the_shield_off(text, name):
    """Regression: the label that PRECEDES a name in minutes was cancelling the redaction.

    The ordinary-head branch was written as "refuse unless the last token is a listed surname,
    THEN strip the label" -- and the refusal came first, so the stripping never ran for
    anything else. "Responsavel Bruno Zanchetta" published the surname; with an unlisted given
    name too, "Cliente Wanderleia Kranz" published the whole name. Both main and the parent
    commit redacted every one of these. The genitive form went the same way through
    `_qualify_run`: "Contrato de Marlene da Costa" leaked while "Marlene da Costa" did not.
    """
    result = pii_shield.redact(text)
    for token in name:
        assert token not in result.redacted_text, result.redacted_text


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Falei com Carlos Silva-TI ontem.", "Falei com [[PERSON_NAME_1]]-TI ontem."),
        ("Contato Ana Souza-RH sobre o ponto.", "Contato [[PERSON_NAME_1]]-RH sobre o ponto."),
    ],
)
def test_an_all_caps_department_suffix_is_not_half_a_surname(text, expected):
    """Regression: "only a capital after the hyphen" refused the span and leaked the surname.

    Name-hyphen-department is a standard Brazilian speaker label (-TI, -RH, -DP, -ADM). Only a
    Title Case continuation is the other half of a compound surname, which is what
    `_TITLE_WORD`'s own hyphen branch already says.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text",
    [
        "LOJA CAMPOS fechou ontem.",
        "REGIONAL CAMPOS foi fechada.",
        "GALPAO PRADO liberado.",
        "O calculo do Imposto de Renda mudou este ano.",
        "A Bolsa de Valores fechou em alta.",
        "Uma Boa Tarde a todos os presentes.",
        # What is left after stripping a label is that phrase's own last word, not a name --
        # "Prado" and "Rocha" are on the surname list and are nobody here.
        "Falta o Relatorio de Vendas do Prado.",
        "O Plano de Acao da Rocha.",
    ],
)
def test_ordinary_labels_and_institutions_are_not_people(text):
    """Inverting the genitive default traded a leak for a flood of false positives.

    A third of the surname list doubles as an ordinary noun or place name, so headings and
    institution names were deleted from the text the model reads and filed as people. Measured
    over a corpus of business sentences containing nobody, the false-positive rate went from
    18% of sentences clean on main to 88% clean here.
    """
    assert pii_shield.redact(text).redacted_text == text


@pytest.mark.parametrize(
    "text, expected",
    [
        # Bounded, so an arbitrary run of separators is not a phone...
        (
            "Reservei a 11 ---- 98765 4321 para amanha.",
            "Reservei a 11 ---- 98765 4321 para amanha.",
        ),
        ("protocolo 2024 e ordem 5566 pendente", "protocolo 2024 e ordem 5566 pendente"),
        # ...but every real typing style still is, including the two the module's own
        # docstring lists and a bound of 2 silently dropped.
        ("Meu telefone e 11 98765-4321.", "Meu telefone e [[PHONE_1]]."),
        ("Me liga no 11  98765-4321.", "Me liga no [[PHONE_1]]."),
        ("Contato 11 - 98765-4321 direto.", "Contato [[PHONE_1]] direto."),
        ("Contato 11 / 98765-4321 direto.", "Contato [[PHONE_1]] direto."),
        ("Ligue para (11) 98765-4321 hoje.", "Ligue para [[PHONE_1]] hoje."),
        ("Whatsapp +55 11 98765-4321 amanha.", "Whatsapp [[PHONE_1]] amanha."),
    ],
)
def test_the_phone_separator_is_bounded_but_covers_every_real_form(text, expected):
    """Regression, in both directions.

    Unbounded (`*`), a DDD followed by any run of separators was a phone. Bounded to two, the
    " - " and " / " forms this module documents as supported stopped matching, which is a leak.
    Three is what the real forms need.

    A shape like "Sala 11 - 98765 4321" is admitted and that is deliberate: it is identical to
    a real number and nothing in the text separates them, so the choice is between redacting a
    room number and publishing a phone.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize("prefix", ["", "contato a@b.com "])
def test_a_hyphen_chain_does_not_make_redaction_quadratic(prefix):
    """Two quadratics met on hyphen-dense text, and both are on the request path.

    Pattern 3 widened over the whole chain once per token inside it. And `-` is not a `\\w`, so
    the e-mail pattern's left anchor succeeded after every hyphen and its unbounded local part
    scanned to the end of the string from each one. 154KB of "Ana-B-" took 4.8s; the transcript
    cap is 1_000_000 chars and routers/analyze.py calls redact() synchronously.

    The parametrisation matters: with no "@" the pre-filter skips the e-mail pattern entirely,
    so only the bound on the local part covers the case where the text really does contain an
    address.
    """

    def elapsed(units: int) -> float:
        blob = prefix + "Ana-B-" * units
        return min(_time_redact(blob) for _ in range(3))

    small, large = elapsed(3200), elapsed(12800)
    # 4x the input. Linear predicts ~4x; either quadratic predicts ~16x.
    assert large < small * 8, f"{small:.3f}s vs {large:.3f}s looks quadratic again"


def _time_redact(blob: str) -> float:
    start = time.perf_counter()
    pii_shield.redact(blob)
    return time.perf_counter() - start


def test_a_lone_surname_left_by_a_product_term_is_not_left_in_the_clear():
    """Regression: a one-token run was refused outright.

    "Sr. Jose Protheus da Silva" came out as "[[PERSON_NAME_1]] Protheus da Silva" -- the
    surname in the clear because the run around it was a single token.
    """
    result = pii_shield.redact("Sr. Jose Protheus da Silva")
    assert "Silva" not in result.redacted_text
    assert "Protheus" in result.redacted_text
    # ...and a lone ordinary word in the same position stays put.
    assert pii_shield.redact("Sr. Jose Protheus da Lista").redacted_text.endswith("da Lista")


@pytest.mark.parametrize(
    "text, expected",
    [
        ("O Protheus do Kleber Zanchetta travou.", "O Protheus do [[PERSON_NAME_1]] travou."),
        ("A Sprint do Adilson Bueno atrasou.", "A Sprint do [[PERSON_NAME_1]] atrasou."),
        ("O Backlog da Cleide Zanchetta esta cheio.", "O Backlog da [[PERSON_NAME_1]] esta cheio."),
        ("O acesso ao Protheus de Jocimar Bonfim.", "O acesso ao Protheus de [[PERSON_NAME_1]]."),
    ],
)
def test_a_product_name_beside_a_person_does_not_switch_the_redaction_off(text, expected):
    """Regression: a negative-list term in the run suppressed a real name.

    "O Protheus do Kleber Zanchetta travou" came out UNTOUCHED while the same sentence with
    "sistema" in place of "Protheus" redacted correctly. A negative term forces the
    qualification path, which demands a listed given name or surname -- and most real names
    are on neither list, which is exactly why an unsplit match is trusted without them. A run
    opening on a genitive preposition is what the negative term OWNS, and the owner of a
    product is a person. "o Protheus do fulano" is everyday speech in these transcripts.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text",
    [
        "Dr. Antônio Gonçalves aprovou.",
        "Patrícia Assunção revisou.",
        "A Sra. Conceição Nogueira assinou.",
    ],
)
def test_decomposed_input_is_redacted_exactly_like_composed_input(text):
    """Regression: NFD input got the placeholder spliced into the middle of the name.

    macOS filesystems and some ASR exports hand over decomposed text -- `A` + U+0301 instead
    of `Á`. Every pattern here assumes one code point per letter, and both `\\b` and
    `.isalpha()` treat a combining mark as a boundary, so NFD("Dr. Antônio Gonçalves aprovou.")
    came out as "[[PERSON_NAME_1]]̂nio Gonçalves aprovou." -- name spliced, rest in the clear,
    and `_hash` filing a value nobody wrote. pt-BR names are accent-dense.
    """
    composed = pii_shield.redact(unicodedata.normalize("NFC", text)).redacted_text
    decomposed = pii_shield.redact(unicodedata.normalize("NFD", text)).redacted_text
    assert composed == decomposed
    assert not re.search(r"\]\]\w", decomposed), decomposed
    assert "[[PERSON_NAME_1]]" in decomposed


def test_the_boundary_guard_counts_a_combining_mark_as_inside_the_word():
    """The second lock, tested where it is reachable.

    `redact` normalizes to NFC, so nothing decomposed survives to reach
    `_ends_on_a_word_boundary` -- mutating this guard off does not change any output of
    `redact`, which means the test above cannot cover it. It is kept because the guard is
    what makes the boundary rule true independently of who calls it, and an untested guard is
    the kind of thing that gets "simplified" away. Asserted here directly instead of
    pretending the end-to-end test reaches it.
    """
    decomposed = unicodedata.normalize("NFD", "Núñez")
    assert decomposed[1] == "u" and unicodedata.combining(decomposed[2])
    # A cut between the "u" and its own acute accent is inside the word.
    assert not pii_shield._ends_on_a_word_boundary(2, decomposed)
    # Control: the end of the string, and a cut before a space, are real boundaries.
    assert pii_shield._ends_on_a_word_boundary(len(decomposed), decomposed)
    assert pii_shield._ends_on_a_word_boundary(3, "Ana Souza")


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Ana Paula Silva-Costa assinou.", "[[PERSON_NAME_1]] assinou."),
        ("Maria Luiza Nunes-Ferreira revisou.", "[[PERSON_NAME_1]] revisou."),
    ],
)
def test_a_hyphenated_compound_surname_is_one_token(text, expected):
    """Regression: the match stopped at the hyphen and the second half leaked.

    `\\b` holds between a letter and `-`, and `-` is not `.isalpha()`, so the boundary guard
    waved the cut through: "Ana Paula Silva-Costa" became "[[PERSON_NAME_1]]-Costa" -- a
    corrupted token for the model and half the surname in the clear.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text, expected",
    [
        ("CARLOS SILVA: fechamos o escopo.", "[[PERSON_NAME_1]]: fechamos o escopo."),
        ("ANA SOUZA ficou de responder.", "[[PERSON_NAME_1]] ficou de responder."),
        ("Assinado por PATRICIA NOGUEIRA.", "Assinado por [[PERSON_NAME_1]]."),
        (
            "Presente: MARINA ALVES, JOAO PEDRO COSTA.",
            "Presente: [[PERSON_NAME_1]], [[PERSON_NAME_2]].",
        ),
    ],
)
def test_an_all_caps_full_name_is_redacted(text, expected):
    """Regression: all-caps names matched no pattern at all and went to the model whole.

    Speaker labels and attendee lists come upper-cased out of most diarisers and out of
    ordinary minute-taking, and this shield is fed the raw transcript. The docstring on
    `_NAME_TOKEN_RE` claimed `_PERSON_NAME_NEGATIVE_LIST` handled them, which it cannot: that
    list only ever SUPPRESSES a redaction.
    """
    assert pii_shield.redact(text).redacted_text == expected


@pytest.mark.parametrize(
    "text",
    [
        "Vamos usar TOTVS PROTHEUS no SAP.",
        "Precisamos da NOTA FISCAL e do XML.",
        "Integrar CRM ERP via API REST.",
    ],
)
def test_an_all_caps_acronym_string_is_not_a_person(text):
    """Counter-proof for the pattern above: all-caps alone is not a person signal.

    An all-caps pair is more often an acronym string than a name, so unlike the Title Case
    sequence this pattern is not trusted on its shape -- one end has to be on the name lists.
    """
    assert pii_shield.redact(text).redacted_text == text


def test_a_placeholder_from_the_earlier_stage_is_not_read_as_an_all_caps_name():
    """`[[EMAIL_1]]` is all-caps by construction -- a shape no other pattern could match."""
    result = pii_shield.redact("Escreva para ana.souza@acme.com.br e para o CARLOS SILVA.")
    assert result.redacted_text == "Escreva para [[EMAIL_1]] e para o [[PERSON_NAME_1]]."


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Meu telefone e 11 98765-4321.", "Meu telefone e [[PHONE_1]]."),
        ("Contato: (11 98765-4321) urgente.", "Contato: ([[PHONE_1]]) urgente."),
        ("Ligue para (11) 98765-4321 hoje.", "Ligue para [[PHONE_1]] hoje."),
        ("Whatsapp +55 11 98765-4321 amanha.", "Whatsapp [[PHONE_1]] amanha."),
    ],
)
def test_the_phone_pattern_does_not_swallow_the_character_before_it(text, expected):
    """Regression: `\\(?\\s*` did not gate the inner space on the parenthesis.

    An unparenthesised number ate the space in front of it -- "telefone e[[PHONE_1]]" --
    destroying the word separator in the text the model reads and filing
    `_hash(" 11 98765-4321")`. One phone number produced a different hash per typing style, so
    the record could not be reconciled against the number it stands for. Same defect the
    fragment gate was written for, left live in the basic-pattern path.
    """
    assert pii_shield.redact(text).redacted_text == expected


def test_no_redaction_of_any_type_hashes_a_value_with_edge_whitespace():
    """The auditability invariant, over BOTH stages.

    The fragment test calls `_redact_person_names` directly and therefore never reaches
    `_apply_basic_patterns`, where the phone pattern was breaking the same invariant.
    """
    probes = [
        "Meu telefone e 11 98765-4321 e o CPF 529.982.247-25.",
        "Contato: (11 98765-4321) urgente, cartao 4111 1111 1111 1111.",
        "Escreva para ana.souza@acme.com.br ou ligue 11 3003-1234.",
        "Ana Souza Protheus Carlos Silva e Dr. Nogueira de Protheus",
        "CARLOS SILVA: o CNPJ e 11.222.333/0001-81.",
    ]
    hashed: list[str] = []
    real_hash = pii_shield._hash
    pii_shield._hash = lambda v: (hashed.append(v), real_hash(v))[1]
    try:
        for probe in probes:
            pii_shield.redact(probe)
    finally:
        pii_shield._hash = real_hash

    assert hashed
    for value in hashed:
        assert value == value.strip(), f"{value!r} carries edge whitespace"


def test_redaction_stays_linear_in_the_size_of_the_transcript():
    """Regression: `_is_covered` was a linear scan, making `redact` quadratic.

    `AnalyzeRequest.transcript` allows 1_000_000 chars and `routers/analyze.py` calls this
    synchronously, so the shape of the curve is a production concern, not a micro-benchmark:
    a legal-size request took over a minute of pure CPU before the LLM call even started.

    Wall-clock thresholds would be flaky on shared CI, so what is asserted is the RATIO --
    doubling the input must not much more than double the work. Quadratic gives ~4x.
    """
    unit = "Reuniao com Ana Souza e Dr. Carlos Silva sobre o Protheus da Marina Alves. "

    def elapsed(kb: int) -> float:
        blob = unit * (kb * 1024 // len(unit))
        best = min(_time_once(blob) for _ in range(3))
        return best

    def _time_once(blob: str) -> float:
        start = time.perf_counter()
        pii_shield.redact(blob)
        return time.perf_counter() - start

    small, large = elapsed(50), elapsed(200)
    # 4x the input. Linear predicts ~4x the time; quadratic predicts ~16x.
    assert large < small * 8, f"{small:.3f}s at 50KB vs {large:.3f}s at 200KB looks superlinear"


def test_a_surname_is_enough_when_the_given_name_is_not_on_the_list():
    """Regressao: ler so o primeiro token deixava a maioria dos nomes BR passar em claro.

    _BR_TOP_NAMES tem 300 nomes proprios; o pais tem muitos mais. Como todo nome completo
    brasileiro termina em apelido, a cauda e o sinal que a cabeca nao consegue dar.
    """
    for text in (
        "Edson Ribeiro Protheus decidiu",
        "Anderson Nogueira Datasul aprovou",
        "Wanderley Cavalcante Fluig assinou",
    ):
        result = pii_shield.redact(text)
        assert result.redacted_text != text, text
        assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_the_surname_signal_does_not_swallow_composite_company_names():
    """Contraprova: o trecho que sobra tem de acabar em apelido, nao em palavra qualquer."""
    for text in ("Acme Software Solutions fechou", "Acme Financeiro Pro subiu de preco"):
        result = pii_shield.redact(text)
        assert result.redacted_text == text, text


def test_a_job_title_alone_is_not_a_person():
    """Regression: job title as a person signal redacted a phrase with nobody in it."""
    for text in ("Gerente de Contas Oracle confirmou o prazo", "Diretor Comercial Senior aprovou"):
        result = pii_shield.redact(text)
        assert result.redacted_text == text
        assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_acronym_does_not_match():
    """'RM', 'SAP', 'CRM' are all-caps and do not satisfy Title Case."""
    text = "Migramos do SAP para o RM via CRM"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_sentence_start_word_not_redacted():
    """An isolated Title Case word at the sentence start is not a name."""
    text = "Reuniao alinhou proxima entrega"
    result = pii_shield.redact(text)
    assert result.redacted_text == text


# --------------------------------------------------------------------------- #
# Interplay with other PII types
# --------------------------------------------------------------------------- #


def test_email_does_not_interfere_with_person_name():
    """The name inside the e-mail must not be redacted twice."""
    text = "carlos@acme.com agendou com Marina"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[EMAIL_1]] agendou com [[PERSON_NAME_1]]"
    types = [r.type for r in result.redactions]
    assert PiiType.EMAIL in types
    assert PiiType.PERSON_NAME in types


def test_email_with_name_in_local_part_only_redacts_email():
    """Marina inside the email does not generate an extra PERSON_NAME."""
    text = "Mande pra marina@empresa.com.br"
    result = pii_shield.redact(text)
    assert "[[EMAIL_1]]" in result.redacted_text
    # no PERSON_NAME because Marina is inside the email range
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_full_pii_mix():
    """E-mail, phone, CPF and name in the same text."""
    text = "Marina Alves (CPF 123.456.789-00) ligou de (11) 98888-7777 para joao@cliente.com.br"
    result = pii_shield.redact(text)
    redacted = result.redacted_text
    assert "Marina Alves" not in redacted
    assert "123.456.789-00" not in redacted
    assert "98888-7777" not in redacted
    assert "joao@cliente.com.br" not in redacted
    types = {r.type for r in result.redactions}
    assert PiiType.PERSON_NAME in types
    assert PiiType.CPF in types
    assert PiiType.PHONE in types
    assert PiiType.EMAIL in types


# --------------------------------------------------------------------------- #
# CREDIT CARD -- leak regression via DOT separator + Luhn
# (audit 2026-06-03, ADR 0012)
# --------------------------------------------------------------------------- #


def test_card_with_dot_separator_is_redacted():
    """Audit probe: '4111.1111.1111.1111' leaked raw. Now it redacts."""
    text = "4111.1111.1111.1111"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[CREDIT_CARD_1]]"
    assert "4111.1111.1111.1111" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_card_with_dot_separator_inline_is_redacted():
    """Audit probe: card with dots in the middle of a sentence."""
    text = "Cartao 4111.1111.1111.1111 venceu"
    result = pii_shield.redact(text)
    assert result.redacted_text == "Cartao [[CREDIT_CARD_1]] venceu"
    assert "4111.1111.1111.1111" not in result.redacted_text


def test_card_with_space_and_hyphen_separators_still_redacted():
    """Legacy separators (space/hyphen) keep working after the change."""
    for raw in ("4111 1111 1111 1111", "4111-1111-1111-1111"):
        result = pii_shield.redact(raw)
        assert result.redacted_text == "[[CREDIT_CARD_1]]"
        assert raw not in result.redacted_text


def test_amex_card_is_redacted():
    """Amex (15 digits, prefix 34/37) with valid Luhn is redacted."""
    text = "Amex 378282246310005 no arquivo"
    result = pii_shield.redact(text)
    assert "378282246310005" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_non_luhn_16_digits_not_treated_as_card():
    """Negative: 16 digits that do NOT pass Luhn are not cards (precision)."""
    text = "Pedido 1234567890123456 enviado"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_non_luhn_16_digits_with_dots_not_treated_as_card():
    """Negative: 4x4 sequence with dots but without a valid Luhn is not a card."""
    text = "1234.5678.9012.3456"
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_long_phone_not_treated_as_card():
    """Negative: a long phone with DDD must not be classified as a card."""
    text = "Ligue para (11) 98888-7777 hoje"
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)
    assert any(r.type == PiiType.PHONE for r in result.redactions)


# --------------------------------------------------------------------------- #
# CPF / CNPJ -- leak regression via SPACE separator
# (audit 2026-06-03, ADR 0012)
# --------------------------------------------------------------------------- #


def test_cpf_with_space_groups_is_redacted():
    """Audit probe: 'Meu CPF e 111 444 777 35' leaked raw. Now it redacts."""
    text = "Meu CPF e 111 444 777 35"
    result = pii_shield.redact(text)
    assert result.redacted_text == "Meu CPF e [[CPF_1]]"
    assert "111 444 777 35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cnpj_with_space_groups_is_redacted():
    """CNPJ with space-separated groups is redacted (valid DV)."""
    text = "CNPJ 11 222 333 0001 81"
    result = pii_shield.redact(text)
    assert result.redacted_text == "CNPJ [[CNPJ_1]]"
    assert "11 222 333 0001 81" not in result.redacted_text
    assert any(r.type == PiiType.CNPJ for r in result.redactions)


def test_spaced_cpf_with_invalid_dv_not_redacted():
    """Negative: spaced groups with an invalid DV are not redacted (precision)."""
    text = "Codigo 123 456 789 00 do lote"
    result = pii_shield.redact(text)
    assert "123 456 789 00" in result.redacted_text
    assert not any(r.type == PiiType.CPF for r in result.redactions)


# --------------------------------------------------------------------------- #
# Direct validators: _validate_cpf / _validate_cnpj / _validate_card / _luhn_ok
# (raises coverage of the DV branches — ADR 0018 requires >85% on PII)
# --------------------------------------------------------------------------- #


def test_validate_cpf_accepts_valid_dv():
    assert pii_shield._validate_cpf("11144477735") is True


def test_validate_cpf_rejects_invalid_dv():
    assert pii_shield._validate_cpf("11144477730") is False


def test_validate_cpf_rejects_trivial_sequence():
    """Trivial sequence (all identical digits) has a 'valid' DV but is rejected."""
    assert pii_shield._validate_cpf("11111111111") is False


def test_validate_cpf_rejects_wrong_length():
    assert pii_shield._validate_cpf("1114447773") is False  # 10 digits
    assert pii_shield._validate_cpf("111444777355") is False  # 12 digits


def test_validate_cpf_rejects_non_digits():
    assert pii_shield._validate_cpf("111.444.777") is False


def test_validate_cnpj_accepts_valid_dv():
    assert pii_shield._validate_cnpj("11222333000181") is True


def test_validate_cnpj_rejects_invalid_dv():
    assert pii_shield._validate_cnpj("11222333000180") is False


def test_validate_cnpj_rejects_trivial_sequence():
    assert pii_shield._validate_cnpj("11111111111111") is False


def test_validate_cnpj_rejects_wrong_length():
    assert pii_shield._validate_cnpj("1122233300018") is False  # 13 digits


def test_validate_card_accepts_luhn_valid():
    assert pii_shield._validate_card("4111 1111 1111 1111") is True
    assert pii_shield._validate_card("378282246310005") is True  # amex 15


def test_validate_card_rejects_luhn_invalid():
    assert pii_shield._validate_card("1234567890123456") is False


def test_validate_card_rejects_wrong_length():
    assert pii_shield._validate_card("41111111") is False  # 8 digits


def test_luhn_ok():
    assert pii_shield._luhn_ok("4111111111111111") is True
    assert pii_shield._luhn_ok("1234567890123456") is False


# --------------------------------------------------------------------------- #
# AUDIT 2026-06-16 (ADR 0012) — structured PII bypass by formatting.
# Regression of cases 1,2,3,7,8,10 (PHONE), 11 (CNPJ), 12,13,14 (CPF) and 15
# (Diners card) from the report `.challenge-build/pii-leak-hunt-report.md`.
# In all of them: the ORIGINAL number must not survive in `redacted_text`.
# --------------------------------------------------------------------------- #


def test_phone_isolated_ninth_digit_with_parens_redacted():
    """Case 1: mobile 9th digit dictated on its own, with DDD in parentheses."""
    text = "meu cel e (11) 9 8765-4321 anota"
    result = pii_shield.redact(text)
    assert "(11) 9 8765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_isolated_ninth_digit_no_parens_redacted():
    """Case 2: 9th digit on its own, no parentheses, split by a space."""
    text = "cliente fone 11 9 8765 4321 anotado"
    result = pii_shield.redact(text)
    assert "11 9 8765 4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_international_br_with_isolated_nine_redacted():
    """Case 3: +55 (11) 9 9988-7766 — BR international with a loose 9."""
    text = "+55 (11) 9 9988-7766 completo"
    result = pii_shield.redact(text)
    assert "+55 (11) 9 9988-7766" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_space_inside_parens_redacted():
    """Case 7: inner spaces in the parentheses — ( 11 ) 98765-4321."""
    text = "( 11 ) 98765-4321 espaco no parentese"
    result = pii_shield.redact(text)
    assert "( 11 ) 98765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_ddd_with_leading_zero_redacted():
    """Case 8: old DDD with a zero (3 digits) — (011) 98765-4321."""
    text = "fone (011) 98765-4321 antigo"
    result = pii_shield.redact(text)
    assert "(011) 98765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_slash_separator_redacted():
    """Case 10: slash separator — 11/98765/4321."""
    text = "tel 11/98765/4321 barra"
    result = pii_shield.redact(text)
    assert "11/98765/4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_canonical_format_still_redacted():
    """Anti-regression: the canonical format (11) 98888-7777 stays redacted."""
    text = "no (11) 98888-7777 ok"
    result = pii_shield.redact(text)
    assert "(11) 98888-7777" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_cnpj_dots_only_separator_redacted():
    """Case 11: CNPJ with a DOT between all groups — 11.222.333.0001.81."""
    text = "cnpj 11.222.333.0001.81 so pontos"
    result = pii_shield.redact(text)
    assert result.redacted_text == "cnpj [[CNPJ_1]] so pontos"
    assert "11.222.333.0001.81" not in result.redacted_text
    assert any(r.type == PiiType.CNPJ for r in result.redactions)


def test_cpf_mixed_dot_and_space_separators_redacted():
    """Case 12: CPF mixing dots + a space before the DV — 111.444.777 35."""
    text = "cpf 111.444.777 35 misto"
    result = pii_shield.redact(text)
    assert result.redacted_text == "cpf [[CPF_1]] misto"
    assert "111.444.777 35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cpf_slash_separators_redacted():
    """Case 13: CPF with slashes — 111/444/777-35."""
    text = "cpf com barra 111/444/777-35 estranho"
    result = pii_shield.redact(text)
    assert "111/444/777-35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cpf_hyphen_only_separators_redacted():
    """Case 14: CPF with hyphens only between groups — 111-444-777-35."""
    text = "cpf 111-444-777-35 so hifen"
    result = pii_shield.redact(text)
    assert "111-444-777-35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_diners_14_digit_card_redacted():
    """Case 15: 14-digit Diners card (4-4-4-2), valid Luhn — 3056 9309 0259 04."""
    text = "diners 3056 9309 0259 04 cartao"
    result = pii_shield.redact(text)
    assert result.redacted_text == "diners [[CREDIT_CARD_1]] cartao"
    assert "3056 9309 0259 04" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_diners_14_digit_card_raw_redacted():
    """14-digit Diners with no separators also passes (Luhn gate)."""
    text = "30569309025904"
    result = pii_shield.redact(text)
    assert "30569309025904" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


# --------------------------------------------------------------------------- #
# Anti-false-positive: the NEW tolerant patterns (arbitrary separator) only
# redact with a valid DV/Luhn. Invalid numbers are NOT redacted.
# (The same invalid numbers from section 2.4 of the report, but in a
# non-canonical format — which is exactly what the tolerant patterns govern.)
# --------------------------------------------------------------------------- #


def test_invalid_cpf_tolerant_separators_not_redacted():
    """DV blocks it: an invalid CPF with arbitrary separators is not redacted."""
    for raw in ("100 000 000 50", "123 456 789 00", "999-888-777-66", "100/000/000-50"):
        text = f"codigo {raw} lote"
        result = pii_shield.redact(text)
        assert raw in result.redacted_text, f"OVER-REDIGIDO (DV invalido deveria passar): {raw}"
        assert not any(r.type == PiiType.CPF for r in result.redactions)


def test_invalid_cnpj_dots_not_redacted():
    """DV blocks it: an invalid dots-only CNPJ is not redacted."""
    text = "registro 12.345.678.0001.00 talvez"
    result = pii_shield.redact(text)
    assert "12.345.678.0001.00" in result.redacted_text
    assert not any(r.type == PiiType.CNPJ for r in result.redactions)


def test_16_digit_tracking_code_not_treated_as_card():
    """Anti-FP: a 16-digit order/tracking code WITHOUT valid Luhn is not a card."""
    text = "codigo de rastreio 7531594562130864 do pedido"
    result = pii_shield.redact(text)
    assert "7531594562130864" in result.redacted_text
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_14_digit_non_luhn_not_treated_as_card():
    """Anti-FP: 14 digits (same grouping as Diners) without Luhn is not a card."""
    text = "pedido 3056 9309 0259 09 invalido"  # last digit breaks Luhn
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)
