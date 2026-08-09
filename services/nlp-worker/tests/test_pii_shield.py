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


def test_redacts_accented_first_name_regression():
    """Regressao (jun/2026, leak real em producao): nomes ACENTUADOS da lista
    vazavam porque `_BR_TOP_NAMES` e escrita SEM acento e a comparacao era
    `casefold()` sensivel a acento -- 'Patrícia' ('patrícia') nunca casava com
    'Patricia' ('patricia'). O accent-fold (NFKD) corrige a classe inteira."""
    for name in ("Patrícia", "Antônio", "André", "João", "Mário", "Mônica", "Vitória", "César"):
        text = f"A {name} ficou de enviar o contrato"
        result = pii_shield.redact(text)
        assert name not in result.redacted_text, f"VAZOU nome acentuado: {name}"
        assert any(r.type == PiiType.PERSON_NAME for r in result.redactions), (
            f"{name} nao gerou redacao PERSON_NAME"
        )


def test_accent_fold_does_not_break_negative_list():
    """Termos da negative list continuam NAO sendo redigidos apos o accent-fold."""
    text = "Vamos rodar no Azure com Python e Spring, integrando com Salesforce"
    result = pii_shield.redact(text)
    for term in ("Azure", "Python", "Spring", "Salesforce"):
        assert term in result.redacted_text, f"{term} foi redigido por engano"


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


def test_negative_token_does_not_shield_the_name_next_to_it():
    """Produto encostado no nome nao pode desligar a redacao do nome.

    Regressao: `_is_negative` era all-or-nothing -- um unico token da negative list
    descartava o match inteiro da sequencia Title Case. Como a regex e gulosa, escrever
    "Ana Souza Protheus" fazia "Ana Souza" sair em claro.
    """
    result = pii_shield.redact("Reuniao com Ana Souza Protheus na terca")
    assert "Ana Souza" not in result.redacted_text
    assert "Protheus" in result.redacted_text
    assert any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_negative_token_between_two_names_keeps_the_longest_run():
    """Com o ofensor no meio, sobra o maior trecho contiguo limpo."""
    result = pii_shield.redact("Acme Protheus Ana Souza fechou o contrato")
    assert "Ana Souza" not in result.redacted_text
    assert "Acme" in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_all_negative_tokens_still_redact_nothing():
    """Contraprova do recorte: sem nenhum token limpo, nada e redigido."""
    text = "Comparamos Protheus Datasul lado a lado"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.PERSON_NAME for r in result.redactions)


def test_a_negative_token_between_two_names_redacts_both():
    """Regressao: so o primeiro trecho limpo sobrevivia e o segundo apelido saia em claro."""
    result = pii_shield.redact("Ana Souza Protheus Carlos Silva")
    assert "Ana Souza" not in result.redacted_text
    assert "Carlos Silva" not in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_a_trimmed_prefix_claim_does_not_swallow_a_clean_sequence():
    """Regressao: o span recortado do Padrao 1 bloqueava um match limpo do Padrao 2."""
    result = pii_shield.redact("Ribeiro Alves Dr. Ana Protheus")
    assert "Ribeiro Alves" not in result.redacted_text
    assert "Protheus" in result.redacted_text


def test_a_trim_never_splices_a_placeholder_into_a_surname():
    """Regressao: o corte caia no fim do match e colava o placeholder na cauda do apelido."""
    result = pii_shield.redact("Sr. Protheus Carlos Núñez")
    assert "]]ñez" not in result.redacted_text
    assert "Núñez" in result.redacted_text


def test_a_job_title_alone_is_not_a_person():
    """Regressao: cargo como sinal de pessoa redigia frase que nao tem ninguem dentro."""
    for text in ("Gerente de Contas Oracle confirmou o prazo", "Diretor Comercial Senior aprovou"):
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


# --------------------------------------------------------------------------- #
# CARTAO DE CREDITO -- regressao de vazamento por separador PONTO + Luhn
# (auditoria 2026-06-03, ADR 0012)
# --------------------------------------------------------------------------- #


def test_card_with_dot_separator_is_redacted():
    """Probe da auditoria: '4111.1111.1111.1111' vazava cru. Agora redige."""
    text = "4111.1111.1111.1111"
    result = pii_shield.redact(text)
    assert result.redacted_text == "[[CREDIT_CARD_1]]"
    assert "4111.1111.1111.1111" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_card_with_dot_separator_inline_is_redacted():
    """Probe da auditoria: cartao com ponto no meio de uma frase."""
    text = "Cartao 4111.1111.1111.1111 venceu"
    result = pii_shield.redact(text)
    assert result.redacted_text == "Cartao [[CREDIT_CARD_1]] venceu"
    assert "4111.1111.1111.1111" not in result.redacted_text


def test_card_with_space_and_hyphen_separators_still_redacted():
    """Separadores legados (espaco/hifen) seguem funcionando apos a mudanca."""
    for raw in ("4111 1111 1111 1111", "4111-1111-1111-1111"):
        result = pii_shield.redact(raw)
        assert result.redacted_text == "[[CREDIT_CARD_1]]"
        assert raw not in result.redacted_text


def test_amex_card_is_redacted():
    """Amex (15 digitos, prefixo 34/37) com Luhn valido eh redigido."""
    text = "Amex 378282246310005 no arquivo"
    result = pii_shield.redact(text)
    assert "378282246310005" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_non_luhn_16_digits_not_treated_as_card():
    """Negativo: 16 digitos que NAO passam Luhn nao viram cartao (precisao)."""
    text = "Pedido 1234567890123456 enviado"
    result = pii_shield.redact(text)
    assert result.redacted_text == text
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_non_luhn_16_digits_with_dots_not_treated_as_card():
    """Negativo: sequencia 4x4 com pontos mas sem Luhn valido nao eh cartao."""
    text = "1234.5678.9012.3456"
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_long_phone_not_treated_as_card():
    """Negativo: telefone longo com DDD nao deve ser classificado como cartao."""
    text = "Ligue para (11) 98888-7777 hoje"
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)
    assert any(r.type == PiiType.PHONE for r in result.redactions)


# --------------------------------------------------------------------------- #
# CPF / CNPJ -- regressao de vazamento por separador ESPACO
# (auditoria 2026-06-03, ADR 0012)
# --------------------------------------------------------------------------- #


def test_cpf_with_space_groups_is_redacted():
    """Probe da auditoria: 'Meu CPF e 111 444 777 35' vazava cru. Agora redige."""
    text = "Meu CPF e 111 444 777 35"
    result = pii_shield.redact(text)
    assert result.redacted_text == "Meu CPF e [[CPF_1]]"
    assert "111 444 777 35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cnpj_with_space_groups_is_redacted():
    """CNPJ com grupos separados por espaco eh redigido (DV valido)."""
    text = "CNPJ 11 222 333 0001 81"
    result = pii_shield.redact(text)
    assert result.redacted_text == "CNPJ [[CNPJ_1]]"
    assert "11 222 333 0001 81" not in result.redacted_text
    assert any(r.type == PiiType.CNPJ for r in result.redactions)


def test_spaced_cpf_with_invalid_dv_not_redacted():
    """Negativo: grupos espacados com DV invalido nao sao redigidos (precisao)."""
    text = "Codigo 123 456 789 00 do lote"
    result = pii_shield.redact(text)
    assert "123 456 789 00" in result.redacted_text
    assert not any(r.type == PiiType.CPF for r in result.redactions)


# --------------------------------------------------------------------------- #
# Validadores diretos: _validate_cpf / _validate_cnpj / _validate_card / _luhn_ok
# (sobe cobertura dos ramos de DV — ADR 0018 exige >85% em PII)
# --------------------------------------------------------------------------- #


def test_validate_cpf_accepts_valid_dv():
    assert pii_shield._validate_cpf("11144477735") is True


def test_validate_cpf_rejects_invalid_dv():
    assert pii_shield._validate_cpf("11144477730") is False


def test_validate_cpf_rejects_trivial_sequence():
    """Sequencia trivial (todos digitos iguais) tem DV 'valido' mas eh rejeitada."""
    assert pii_shield._validate_cpf("11111111111") is False


def test_validate_cpf_rejects_wrong_length():
    assert pii_shield._validate_cpf("1114447773") is False  # 10 digitos
    assert pii_shield._validate_cpf("111444777355") is False  # 12 digitos


def test_validate_cpf_rejects_non_digits():
    assert pii_shield._validate_cpf("111.444.777") is False


def test_validate_cnpj_accepts_valid_dv():
    assert pii_shield._validate_cnpj("11222333000181") is True


def test_validate_cnpj_rejects_invalid_dv():
    assert pii_shield._validate_cnpj("11222333000180") is False


def test_validate_cnpj_rejects_trivial_sequence():
    assert pii_shield._validate_cnpj("11111111111111") is False


def test_validate_cnpj_rejects_wrong_length():
    assert pii_shield._validate_cnpj("1122233300018") is False  # 13 digitos


def test_validate_card_accepts_luhn_valid():
    assert pii_shield._validate_card("4111 1111 1111 1111") is True
    assert pii_shield._validate_card("378282246310005") is True  # amex 15


def test_validate_card_rejects_luhn_invalid():
    assert pii_shield._validate_card("1234567890123456") is False


def test_validate_card_rejects_wrong_length():
    assert pii_shield._validate_card("41111111") is False  # 8 digitos


def test_luhn_ok():
    assert pii_shield._luhn_ok("4111111111111111") is True
    assert pii_shield._luhn_ok("1234567890123456") is False


# --------------------------------------------------------------------------- #
# AUDITORIA 2026-06-16 (ADR 0012) — bypass de PII estruturada por formato.
# Regressao dos casos 1,2,3,7,8,10 (PHONE), 11 (CNPJ), 12,13,14 (CPF) e 15
# (cartao Diners) do relatorio `.challenge-build/pii-leak-hunt-report.md`.
# Em todos: o numero ORIGINAL nao pode sobrar em `redacted_text`.
# --------------------------------------------------------------------------- #


def test_phone_isolated_ninth_digit_with_parens_redacted():
    """Caso 1: 9o digito do celular ditado solto, com DDD em parenteses."""
    text = "meu cel e (11) 9 8765-4321 anota"
    result = pii_shield.redact(text)
    assert "(11) 9 8765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_isolated_ninth_digit_no_parens_redacted():
    """Caso 2: 9o digito solto, sem parenteses, separado por espaco."""
    text = "cliente fone 11 9 8765 4321 anotado"
    result = pii_shield.redact(text)
    assert "11 9 8765 4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_international_br_with_isolated_nine_redacted():
    """Caso 3: +55 (11) 9 9988-7766 — internacional BR com 9 solto."""
    text = "+55 (11) 9 9988-7766 completo"
    result = pii_shield.redact(text)
    assert "+55 (11) 9 9988-7766" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_space_inside_parens_redacted():
    """Caso 7: espacos internos no parentese — ( 11 ) 98765-4321."""
    text = "( 11 ) 98765-4321 espaco no parentese"
    result = pii_shield.redact(text)
    assert "( 11 ) 98765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_ddd_with_leading_zero_redacted():
    """Caso 8: DDD antigo com zero (3 digitos) — (011) 98765-4321."""
    text = "fone (011) 98765-4321 antigo"
    result = pii_shield.redact(text)
    assert "(011) 98765-4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_slash_separator_redacted():
    """Caso 10: separador barra — 11/98765/4321."""
    text = "tel 11/98765/4321 barra"
    result = pii_shield.redact(text)
    assert "11/98765/4321" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_phone_canonical_format_still_redacted():
    """Anti-regressao: o formato canonico (11) 98888-7777 continua redigido."""
    text = "no (11) 98888-7777 ok"
    result = pii_shield.redact(text)
    assert "(11) 98888-7777" not in result.redacted_text
    assert any(r.type == PiiType.PHONE for r in result.redactions)


def test_cnpj_dots_only_separator_redacted():
    """Caso 11: CNPJ com PONTO entre todos os grupos — 11.222.333.0001.81."""
    text = "cnpj 11.222.333.0001.81 so pontos"
    result = pii_shield.redact(text)
    assert result.redacted_text == "cnpj [[CNPJ_1]] so pontos"
    assert "11.222.333.0001.81" not in result.redacted_text
    assert any(r.type == PiiType.CNPJ for r in result.redactions)


def test_cpf_mixed_dot_and_space_separators_redacted():
    """Caso 12: CPF misto pontos + espaco antes do DV — 111.444.777 35."""
    text = "cpf 111.444.777 35 misto"
    result = pii_shield.redact(text)
    assert result.redacted_text == "cpf [[CPF_1]] misto"
    assert "111.444.777 35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cpf_slash_separators_redacted():
    """Caso 13: CPF com barra — 111/444/777-35."""
    text = "cpf com barra 111/444/777-35 estranho"
    result = pii_shield.redact(text)
    assert "111/444/777-35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_cpf_hyphen_only_separators_redacted():
    """Caso 14: CPF so com hifen entre grupos — 111-444-777-35."""
    text = "cpf 111-444-777-35 so hifen"
    result = pii_shield.redact(text)
    assert "111-444-777-35" not in result.redacted_text
    assert any(r.type == PiiType.CPF for r in result.redactions)


def test_diners_14_digit_card_redacted():
    """Caso 15: cartao Diners 14 digitos (4-4-4-2), Luhn valido — 3056 9309 0259 04."""
    text = "diners 3056 9309 0259 04 cartao"
    result = pii_shield.redact(text)
    assert result.redacted_text == "diners [[CREDIT_CARD_1]] cartao"
    assert "3056 9309 0259 04" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_diners_14_digit_card_raw_redacted():
    """Diners 14 digitos sem separadores tambem passa (Luhn gate)."""
    text = "30569309025904"
    result = pii_shield.redact(text)
    assert "30569309025904" not in result.redacted_text
    assert any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


# --------------------------------------------------------------------------- #
# Anti-falso-positivo: os NOVOS patterns tolerantes (separador arbitrario)
# so redigem com DV/Luhn valido. Numeros invalidos NAO sao redigidos.
# (Os mesmos numeros invalidos da secao 2.4 do relatorio, mas em formato
# nao-canonico — que e exatamente o que os patterns tolerantes governam.)
# --------------------------------------------------------------------------- #


def test_invalid_cpf_tolerant_separators_not_redacted():
    """DV bloqueia: CPF invalido com separadores arbitrarios nao e redigido."""
    for raw in ("100 000 000 50", "123 456 789 00", "999-888-777-66", "100/000/000-50"):
        text = f"codigo {raw} lote"
        result = pii_shield.redact(text)
        assert raw in result.redacted_text, f"OVER-REDIGIDO (DV invalido deveria passar): {raw}"
        assert not any(r.type == PiiType.CPF for r in result.redactions)


def test_invalid_cnpj_dots_not_redacted():
    """DV bloqueia: CNPJ invalido so-pontos nao e redigido."""
    text = "registro 12.345.678.0001.00 talvez"
    result = pii_shield.redact(text)
    assert "12.345.678.0001.00" in result.redacted_text
    assert not any(r.type == PiiType.CNPJ for r in result.redactions)


def test_16_digit_tracking_code_not_treated_as_card():
    """Anti-FP: codigo de pedido/rastreio de 16 digitos SEM Luhn valido nao vira cartao."""
    text = "codigo de rastreio 7531594562130864 do pedido"
    result = pii_shield.redact(text)
    assert "7531594562130864" in result.redacted_text
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)


def test_14_digit_non_luhn_not_treated_as_card():
    """Anti-FP: 14 digitos (mesmo grouping do Diners) sem Luhn nao vira cartao."""
    text = "pedido 3056 9309 0259 09 invalido"  # ultimo digito quebra o Luhn
    result = pii_shield.redact(text)
    assert not any(r.type == PiiType.CREDIT_CARD for r in result.redactions)
