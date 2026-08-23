"""Shared PII-shield helpers for the worker's analyzers.

``shield_field`` and ``shield_tree`` were written in ``llm_analyzer`` and lived there, while
``live_analyzer`` shielded its structures through a hand-kept dictionary of field names --
which is the technique ``shield_tree``'s own docstring argues against. The two analyzers
therefore disagreed about the same question, and only one of them was right.

Extracted here for the reason ``prompt_utils`` gives for the prompt helpers: a single source
avoids drift. A fix to the walk that reached one analyzer and not the other would leave the
other with the defect the fix was written for, and the redaction counter would keep reporting
a clean audit trail either way.
"""

from __future__ import annotations

from .pii_shield import redact as pii_redact


def shield_field(value: str, counter: list[int], tenant_terms: frozenset[str] = frozenset()) -> str:
    """Applies PII Shield to an individual field, counting redactions.

    `tenant_terms` is this request's admitted trade names, and passing them here is what stops
    the prompt from contradicting itself. Without it the transcript kept "Kranz Solutions" --
    that is the whole point of finding 5c -- while this block turned the same string into
    `[[PERSON_NAME_1]]`, so the model saw one entity written two ways in a single request.
    Over-redaction, never a leak, but half a feature.

    The shield still decides. These terms are not trusted here any more than anywhere else:
    `redact` runs its two passes and discards the second if a person was freed.
    """
    if not value:
        return value
    out = pii_redact(value, tenant_terms)
    counter[0] += len(out.redactions)
    return out.redacted_text


def shield_tree(
    value: object, counter: list[int], tenant_terms: frozenset[str] = frozenset()
) -> object:
    """Applies the PII Shield to every string leaf of a nested structure.

    Walks dicts and lists instead of naming the fields to cover. The structures this runs over
    are free text from end to end, and a hand-kept list of keys only protects the shape it was
    written against: a field of a type the list did not expect, or one added to the model
    afterwards, stops reaching the shield without anything failing -- and the redaction counter
    then reports a clean audit trail for text that was never inspected. Dict keys are schema
    names, not user input, so they are kept as they are. ADR 0012.
    """
    if isinstance(value, str):
        return shield_field(value, counter, tenant_terms)
    if isinstance(value, dict):
        return {k: shield_tree(v, counter, tenant_terms) for k, v in value.items()}
    if isinstance(value, list):
        return [shield_tree(item, counter, tenant_terms) for item in value]
    return value
