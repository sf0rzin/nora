"""Helpers compartilhados de prompt dos analyzers do worker.

``load_prompt`` (parse das secoes ## SYSTEM / ## USER) e ``render_template``
(substituicao de placeholders ``{{key}}`` com escape anti prompt-template
injection) eram triplicados byte-a-byte entre ``llm_analyzer``,
``live_analyzer`` e ``split_analyzer``. Uma unica fonte evita drift — ex.: um
fix no escape que so chegasse a um dos analyzers deixaria os outros vulneraveis.
"""

from __future__ import annotations

import re
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

_SYSTEM_RE = re.compile(r"##\s*SYSTEM\s*\n(.*?)(?=\n##\s*USER)", re.DOTALL)
_USER_RE = re.compile(r"##\s*USER\s*\n(.*)", re.DOTALL)


def load_prompt(version: str) -> tuple[str, str]:
    """Carrega ``prompts/{version}.md`` e retorna as secoes (system, user)."""
    path = PROMPTS_DIR / f"{version}.md"
    if not path.exists():
        raise FileNotFoundError(f"Prompt nao encontrado: {path}")

    content = path.read_text(encoding="utf-8")
    system_match = _SYSTEM_RE.search(content)
    user_match = _USER_RE.search(content)
    if not system_match or not user_match:
        raise ValueError(f"Prompt {version}.md deve conter secoes ## SYSTEM e ## USER")

    return system_match.group(1).strip(), user_match.group(1).strip()


def _escape_placeholders(value: str) -> str:
    """Neutraliza placeholders ``{{x}}`` vindos do usuario.

    ``render_template`` faz ``str.replace`` em ordem; se o transcript ou outro
    campo do request contiver ``{{goal_section}}``, ele seria substituido pelo
    conteudo legitimo de ``goal_section`` nas iteracoes seguintes — prompt
    template injection. Trocar ``{{`` por ``{ {`` rompe o casamento sem mudar a
    intencao semantica do texto original.
    """
    if "{{" not in value:
        return value
    return value.replace("{{", "{ {").replace("}}", "} }")


def render_template(template: str, **variables: str) -> str:
    """Substitui placeholders ``{{key}}`` no template, com escape de input do usuario."""
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", _escape_placeholders(value))
    return result
