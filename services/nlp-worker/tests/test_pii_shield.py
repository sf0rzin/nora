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
    """Regression: the cut fell at the match end, gluing the placeholder to the name tail."""
    result = pii_shield.redact("Sr. Protheus Carlos Núñez")
    assert "]]ñez" not in result.redacted_text
    assert "Núñez" in result.redacted_text


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
