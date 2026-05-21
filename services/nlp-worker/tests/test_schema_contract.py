"""Contrato entre o canonical JSON Schema e o output do worker (ADR 0006).

Garante que (a) o schema canonico em docs/ e um JSON Schema valido e (b) a
resposta do worker — incluindo o bloco customerConfidence com accountName —
valida contra esse schema. Mantem models.py e o schema canonico em sincronia.
"""

from __future__ import annotations

import json
import pathlib

import pytest
from jsonschema import Draft202012Validator

from nora_nlp.clients.llm import build_json_schema_for_analysis
from nora_nlp.models import AnalyzeRequest
from nora_nlp.services.stub_analyzer import analyze

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DATA_DIR = REPO_ROOT / "data" / "synthetic"
SCHEMA_PATH = REPO_ROOT / "docs" / "api" / "llm-schemas" / "meeting-analysis-v1.schema.json"


def _canonical_schema() -> dict:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def _stub_response(transcript_file: str, tenant: str) -> dict:
    transcript = (DATA_DIR / "meetings" / transcript_file).read_text(encoding="utf-8")
    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / f"{tenant}.context.json").read_text(encoding="utf-8")
    )
    req = AnalyzeRequest.model_validate(
        {
            "meetingId": "00000000-0000-4000-8000-000000000aaa",
            "tenantId": "00000000-0000-4000-8000-000000000001",
            "language": "pt-BR",
            "transcript": transcript,
            "tenantContext": tenant_ctx,
        }
    )
    # by_alias=True -> camelCase, igual ao que o backend recebe.
    return analyze(req).model_dump(by_alias=True, mode="json")


def test_canonical_schema_is_valid_json_schema():
    """O schema canonico precisa ser um Draft 2020-12 valido."""
    Draft202012Validator.check_schema(_canonical_schema())


def test_canonical_schema_declares_account_name():
    """accountName foi adicionado as propriedades de customerConfidence (Slice 2)."""
    cc = _canonical_schema()["properties"]["customerConfidence"]
    account_name = cc["properties"]["accountName"]
    assert account_name["type"] == ["string", "null"]
    assert account_name["maxLength"] == 120
    # A descricao nao deve mais marcar o campo como reservado/nao emitido.
    desc = cc["description"].upper()
    assert "RESERVADO" not in desc
    assert "AINDA NÃO EMITIDO" not in desc
    assert "EMITIDO" in desc


@pytest.mark.parametrize(
    "transcript,tenant",
    [
        ("01-acme-discovery-lead-novo.txt", "acme-software"),
        ("06-northwind-prospect-poc.txt", "northwind-fintech"),
        ("08-northwind-objecao-integracao-tecnica.txt", "northwind-fintech"),
    ],
)
def test_stub_response_validates_against_canonical_schema(transcript: str, tenant: str):
    """Output do stub (com customerConfidence populado) valida contra o schema canonico."""
    validator = Draft202012Validator(_canonical_schema())
    body = _stub_response(transcript, tenant)
    # Metadados nao fazem parte do schema canonico; o schema usa
    # additionalProperties=false, entao removemos os campos extra do response.
    body.pop("metadata", None)
    body.pop("meetingId", None)
    # Sanity: este transcript exercita o bloco customerConfidence (nao null).
    assert body["customerConfidence"] is not None
    errors = sorted(validator.iter_errors(body), key=lambda e: e.path)
    assert not errors, "\n".join(f"{list(e.path)}: {e.message}" for e in errors)


def test_internal_meeting_response_validates_against_canonical_schema():
    """customerConfidence = null tambem e valido contra o schema (campo nullable)."""
    transcript = (
        "[Ana] Bom dia time, vamos revisar o andamento das tarefas de hoje.\n"
        "[Bruno] Terminei o ajuste no layout da tela inicial ontem a noite.\n"
        "[Ana] Otimo. Alguem travado em algo?\n"
        "[Bruno] Nada por aqui, fluindo bem.\n"
        "[Ana] Entao seguimos. Bom trabalho a todos."
    )
    tenant_ctx = json.loads(
        (DATA_DIR / "tenants" / "solo-launch.context.json").read_text(encoding="utf-8")
    )
    req = AnalyzeRequest.model_validate(
        {
            "meetingId": "00000000-0000-4000-8000-000000000aaa",
            "tenantId": "00000000-0000-4000-8000-000000000001",
            "language": "pt-BR",
            "transcript": transcript,
            "tenantContext": tenant_ctx,
        }
    )
    body = req and analyze(req).model_dump(by_alias=True, mode="json")
    body.pop("metadata", None)
    body.pop("meetingId", None)
    assert body["customerConfidence"] is None
    validator = Draft202012Validator(_canonical_schema())
    errors = list(validator.iter_errors(body))
    assert not errors, "\n".join(f"{list(e.path)}: {e.message}" for e in errors)


def test_strict_llm_schema_matches_canonical_enums():
    """As enums do schema strict (llm.py) batem com o schema canonico (fonte da verdade)."""
    strict = build_json_schema_for_analysis()["properties"]["customerConfidence"]["properties"]
    canon = _canonical_schema()["properties"]["customerConfidence"]["properties"]

    assert strict["band"]["enum"] == canon["band"]["enum"]
    # trend: strict carrega None na lista (serializa pra null, igual ao canonico).
    assert strict["trend"]["enum"] == canon["trend"]["enum"]
    strict_signal = strict["buyingSignals"]["items"]["properties"]["type"]["enum"]
    canon_signal = canon["buyingSignals"]["items"]["properties"]["type"]["enum"]
    assert strict_signal == canon_signal
    strict_obj = strict["objections"]["items"]["properties"]["type"]["enum"]
    canon_obj = canon["objections"]["items"]["properties"]["type"]["enum"]
    assert strict_obj == canon_obj
