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
    """Padrao 3: primeiro nome BR sozinho contra lista hardcoded."""
    text = "O Lucas confirmou a proposta"
    result = pii_shield.redact(text)
    assert result.redacted_text == "O [[PERSON_NAME_1]] confirmou a proposta"
    assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_redacts_name_surname_sequence():
    """Padrao 2: 2 palavras Title Case consecutivas."""
    text = "Marina Alves do RH"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[PERSON_NAME_1]] do RH"
    person = [r for r in result.redactions if r.type == PiiType.PERSON_NAME]
    assert len(person) == 1


def test_redacts_name_with_prefix():
    """Padrao 1: pronome de tratamento + Nome [Sobrenome]."""
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
    """Cada nome detectado recebe um numero novo (sem dedup)."""
    text = "Marina ligou pro Pedro sobre o RM"
    result = pii_shield.redact(text)
    assert result.redacted_text == ("[[PERSON_NAME_1]] ligou pro [[PERSON_NAME_2]] sobre o RM")
    person = [r for r in result.redactions if r.type == PiiType.PERSON_NAME]
    assert len(person) == 2


def test_same_person_repeated_gets_new_number():
    """Decisao explicita do escopo: cada ocorrencia ganha numero novo."""
    text = "Lucas chegou, Lucas falou, Lucas saiu"
    result = pii_shield.redact(text)
    assert "[[PERSON_NAME_1]]" in result.redacted_text
    assert "[[PERSON_NAME_2]]" in result.redacted_text
    assert "[[PERSON_NAME_3]]" in result.redacted_text


# --------------------------------------------------------------------------- #
# PERSON_NAME -- negatives (negative list e nao-nomes)
# --------------------------------------------------------------------------- #


def test_negative_list_blocks_product_pair():
    """'TOTVS Protheus' nao deve virar PERSON_NAME."""
    text = "Vamos usar TOTVS Protheus"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_negative_list_blocks_company_and_product():
    """'A Acme contratou NORA' -- ambos na negative list, texto inalterado."""
    text = "A Acme contratou NORA"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_acronym_does_not_match():
    """'RM', 'SAP', 'CRM' sao all-caps e nao satisfazem Title Case."""
    text = "Migramos do SAP para o RM via CRM"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_sentence_start_word_not_redacted():
    """Palavra Title Case isolada no inicio da frase nao eh nome."""
    text = "Reuniao alinhou proxima entrega"
    result = pii_shield.redact(text)
    assert result.redacted_text == text


# --------------------------------------------------------------------------- #
# Interplay com outros tipos de PII
# --------------------------------------------------------------------------- #


def test_email_does_not_interfere_with_person_name():
    """O nome dentro do e-mail nao deve ser redigido duas vezes."""
    text = "carlos@acme.com agendou com Marina"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[EMAIL_1]] agendou com [[PERSON_NAME_1]]"
    types = [r.type for r in result.redactions]
    assert PiiType.EMAIL in types
    assert PiiType.PERSON_NAME in types


def test_email_with_name_in_local_part_only_redacts_email():
    """Marina dentro do email nao gera PERSON_NAME extra."""
    text = "Mande pra marina@empresa.com.br"
    result = pii_shield.redact(text)
    assert "[[EMAIL_1]]" in result.redacted_text
    # nenhum PERSON_NAME pois Marina esta dentro do range do email
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_full_pii_mix():
    """E-mail, telefone, CPF e nome no mesmo texto."""
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
