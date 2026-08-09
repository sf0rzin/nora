"""Shared prompt helpers for the worker's analyzers.

``load_prompt`` (parsing of the ## SYSTEM / ## USER sections) and ``render_template``
(substitution of ``{{key}}`` placeholders with anti prompt-template
injection escaping) were triplicated byte-for-byte across ``llm_analyzer``,
``live_analyzer`` and ``split_analyzer``. A single source avoids drift — e.g.: a
fix in the escaping reaching only one analyzer would leave the others vulnerable.
"""

from __future__ import annotations

import re
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

_SYSTEM_RE = re.compile(r"##\s*SYSTEM\s*\n(.*?)(?=\n##\s*USER)", re.DOTALL)
_USER_RE = re.compile(r"##\s*USER\s*\n(.*)", re.DOTALL)


def load_prompt(version: str) -> tuple[str, str]:
    """Loads ``prompts/{version}.md`` and returns the (system, user) sections."""
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
    """Neutralizes ``{{x}}`` placeholders coming from the user.

    ``render_template`` does ``str.replace`` in order; if the transcript or another
    request field contains ``{{goal_section}}``, it would be replaced by the
    legitimate content of ``goal_section`` in the following iterations — prompt
    template injection. Swapping ``{{`` for ``{ {`` breaks the match without changing
    the semantic intent of the original text.
    """
    if "{{" not in value:
        return value
    return value.replace("{{", "{ {").replace("}}", "} }")


def render_template(template: str, **variables: str) -> str:
    """Replaces ``{{key}}`` placeholders in the template, escaping user input."""
    result = template
    for key, value in variables.items():
        result = result.replace(f"{{{{{key}}}}}", _escape_placeholders(value))
    return result
