"""A statistical backstop for PERSON_NAME, behind the deterministic shield.

**This is a second layer, not the contract.** The hard gate stays what it has always been in
`pii_shield`: regex, check digits, and the two frequency lists. Everything here can only ADD a
redaction; nothing in this module is allowed to free a span the deterministic pass claimed. That
direction is what makes the layer safe to add — its worst case is over-redaction, which is
measurable and annoying, never a leak that was not already there.

**What it is for.** The corpus measured a 2.12% leak rate that the deterministic rules cannot
close, and the shapes are ordinary rather than exotic: a full name whose middle token is a product
(`Neusa Datasul Nardelli`), a surname behind a genitive (`A proposta da Costa foi aceita`), a lone
off-list Title Case token after a phrase head. All of them have the same cause — the run is split
or shortened until a single token survives, and a token on neither name list is refused by
`_is_a_name_on_its_own`. A list cannot fix that, because the premise of the failure is that the
token is not on a list. A model that reads the sentence can.

**Toponyms are why the model is not just asked for PER.** `Sao Paulo`, `Belo Horizonte` and
`Santa Catarina` are Title Case pairs made of tokens that are also given names, so a naive
person-detector eats every Brazilian place name in the transcript. LOC and GPE spans are collected
and used to veto an overlapping PER span, which is the one thing the model gives us that a list
never could: the sentence decides, not the token.

**Degradation is graceful and silent by design.** If spaCy or the model is not installed, this
module returns no spans and the shield behaves exactly as it did before — a worker that refuses to
start because an optional model is missing would be a worse failure than one that redacts slightly
less. `available()` reports which mode is in force so the corpus harness can say so out loud
instead of publishing one number for two different pipelines.
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)

# The pipeline is loaded once per process and reused. Loading is ~0.4s and the router calls the
# shield synchronously, so doing it per request would be paid on every analysis.
_MODEL = "pt_core_news_sm"

# Only the two components a named-entity pass needs. The tagger, parser, lemmatizer and
# attribute ruler are pure cost here: nothing downstream reads a POS tag or a dependency arc.
_DISABLED = ("tagger", "parser", "attribute_ruler", "lemmatizer", "senter", "morphologizer")

# `None` means "not attempted yet"; `False` means "attempted and unavailable", which is cached
# so a missing model is not re-imported on every call.
_nlp: object | None = None
_load_failed = False

# Entity labels the model emits. PER is what we act on; LOC and GPE veto an overlapping PER,
# and ORG does not — an organisation named after a person is exactly the case where the person
# should still be redacted (`Andrade Gutierrez` names two people).
_PERSON_LABELS = frozenset({"PER", "PERSON"})
_PLACE_LABELS = frozenset({"LOC", "GPE"})

# The model only gets to speak when it has more than one word to go on, and this single number
# is the difference between a layer worth having and one that costs more than it buys. Measured
# over the 5,664-case corpus, all three configurations:
#
#     deterministic only          leak 2.12%   false redaction  9.30%
#     + NER, every PER span       leak 0.35%   false redaction 16.40%
#     + NER, multi-token only     leak 0.39%   false redaction 11.46%
#
# The middle row is the model guessing on lone Title Case tokens, and it guesses in both
# directions: it is right about `A proposta da Costa` and wrong about `Solutions` in
# "Andre Teixeira Solutions confirmou o prazo", where the deterministic pass had correctly left
# the company suffix alone. Requiring two tokens keeps 98% of the leak reduction for 30% of the
# over-redaction, because a multi-word span is evidence and a single capitalised word is a coin
# flip that this corpus shows landing badly 220 times.
_MIN_TOKENS = 2

# A placeholder already written by the deterministic pass. The model happily tags
# `[[PERSON_NAME_1]]` as an entity, and redacting a placeholder would corrupt the text and file
# a hash of something nobody wrote.
_PLACEHOLDER_RE = re.compile(r"\[\[[A-Z_]+_\d+\]\]")


def available() -> bool:
    """Whether the backstop can run in this process. Loads the model on first call."""
    return _pipeline() is not None


def _pipeline():
    global _nlp, _load_failed
    if _nlp is not None or _load_failed:
        return _nlp
    try:
        import spacy

        _nlp = spacy.load(_MODEL, disable=list(_DISABLED))
    except Exception as exc:
        _load_failed = True
        # info, not warning: on a deployment that chose not to ship the model this is the
        # expected state, and a warning on every boot trains people to ignore warnings.
        logger.info(
            "PERSON_NAME NER backstop is off (%s). The deterministic shield is unaffected.",
            exc.__class__.__name__,
        )
        return None
    return _nlp


def person_spans(text: str, is_negative) -> list[tuple[int, int]]:
    """Character spans of person names the deterministic pass did not claim.

    `is_negative` is `pii_shield`'s own token test, passed in rather than imported, so the two
    modules do not import each other and the negative list has exactly one owner.

    A span is returned only if every one of these holds:

      * the model labelled it PER;
      * it does not overlap a LOC or GPE span — the toponym veto;
      * it does not overlap a placeholder already in the text;
      * it holds at least `_MIN_TOKENS` words;
      * at least one of its tokens is not ordinary vocabulary. The test is ANY rather than ALL
        on purpose: `Customer Success` is two known-ordinary tokens and must be refused whole,
        while `Datasul Nardelli` has one and a real surname and must not be.
    """
    nlp = _pipeline()
    if nlp is None or not text.strip():
        return []

    try:
        doc = nlp(text)
    except Exception:
        logger.exception("NER backstop raised; falling back to the deterministic shield alone")
        return []

    places = [(e.start_char, e.end_char) for e in doc.ents if e.label_ in _PLACE_LABELS]
    holes = [(m.start(), m.end()) for m in _PLACEHOLDER_RE.finditer(text)]

    spans: list[tuple[int, int]] = []
    for ent in doc.ents:
        if ent.label_ not in _PERSON_LABELS:
            continue
        start, end = ent.start_char, ent.end_char
        if _overlaps(start, end, places) or _overlaps(start, end, holes):
            continue
        if _is_all_caps(ent.text):
            continue
        trimmed = _trim(ent.text, is_negative)
        if trimmed is None:
            continue
        offset, length = trimmed
        spans.append((start + offset, start + offset + length))
    return spans


def _trim(value: str, is_negative) -> tuple[int, int] | None:
    """Shrinks a span to its name core, or refuses it. Offsets are relative to `value`.

    THE MODEL DOES NOT KNOW WHERE A NAME ENDS, and this is the single correction that makes its
    output usable. It reliably finds that a person is present and just as reliably takes the
    words on either side with them:

        "Comparamos Protheus Datasul lado a lado"   -> PER "Comparamos Protheus Datasul"
        "Acme Software Solutions fechou"            -> PER "Acme Software Solutions"
        "Andre Teixeira Solutions confirmou"        -> PER "Andre Teixeira Solutions"

    Two of those are not people at all and the third is a person wearing a company suffix.
    Dropping known-ordinary words from the EDGES — never from the middle, because `Neusa Datasul
    Nardelli` is one name with a product in it and is exactly what this layer exists to catch —
    leaves the core, and the core is judged on its own: a span that shrinks below `_MIN_TOKENS`
    was never a name, it was the model's enthusiasm.
    """
    tokens = list(re.finditer(r"\w+", value))
    first, last = 0, len(tokens) - 1
    while first <= last and is_negative(tokens[first].group(0)):
        first += 1
    while last >= first and is_negative(tokens[last].group(0)):
        last -= 1
    if last - first + 1 < _MIN_TOKENS:
        return None
    start = tokens[first].start()
    return start, tokens[last].end() - start


def _is_all_caps(value: str) -> bool:
    """No lower-case letter anywhere in the span.

    ALL-CAPS is left entirely to the deterministic side, which has three dedicated patterns for
    it and a set of hard-won rules about headings, speaker labels and verbs. This model is
    trained on normally-cased Portuguese and guesses badly once the casing signal is gone: it
    reads `CARLOS ASSUMIU a frente`, `ANA CONFIRMOU ontem` and `PRAZO FINAL mudou para sexta`
    as people, which are a verb, a verb and a heading. Ceding the whole shape is cheaper than
    teaching the layer three exceptions, and it costs nothing measurable -- the corpus's
    `allcaps` and `allcaps_product_before` families are already at 0% leak without it.
    """
    return not any(ch.islower() for ch in value)


def _overlaps(start: int, end: int, ranges: list[tuple[int, int]]) -> bool:
    return any(start < r_end and r_start < end for r_start, r_end in ranges)
