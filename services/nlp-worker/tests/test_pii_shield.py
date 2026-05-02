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
