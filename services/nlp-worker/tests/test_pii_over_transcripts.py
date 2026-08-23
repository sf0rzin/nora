"""The shield over whole multi-speaker documents, with assertions.

`tests/pii_corpus` is 5,600-odd generated one-line strings and a few dozen hand-written ones. It
is the instrument this repository trusts, and it has one blind spot by construction: nothing in
it is a DOCUMENT. A header block naming the participants, a name repeated across forty turns, a
`.vtt` cue, a `.srt` index line -- the shapes an upload actually arrives in are not in it.

The 17 transcripts in `data/synthetic/meetings/` were already going through `redact` in CI,
because `test_analyze_stub.py` posts them to `/analyze` and the router redacts before the stub.
Nothing asserted anything about the result: every assert in that file is about `summary`,
`decisions`, `risks`, `productivity` and `customerConfidence`, and none about
`piiRedactionsApplied` or about a name. The shield ran and was not measured.

`data/samples/README.md` states the policy this file enforces -- "Esses arquivos devem passar
pelo PII Shield sem falhas -- se quebrarem, e bug no redactor" -- and no test referenced that
directory at all; only `scripts/smoke-e2e.sh` did.

WHAT IS ASSERTED, and why it is not "no name survives". The shield recognises a proper name by
shape plus two frequency lists, so a given name on neither list is published, and that residual
is measured, published and dated in `test_pii_corpus.py`. A blanket "no speaker label survives"
would therefore be asserting the residual away, and would pass today only by luck of which names
these fixtures happen to use. What is asserted instead is the pair that can be true:

  * every speaker whose given name IS on the shield's list is gone from the output, over the
    whole document -- the end-to-end property nothing else checks;
  * the speakers whose names are NOT on it are exactly the recorded set, so a transcript added
    with an unrecognised speaker fails here and somebody decides, rather than the fixture
    quietly widening the untested surface.
"""

from __future__ import annotations

import pathlib
import re

import pytest

from nora_nlp.services import pii_shield
from nora_nlp.services.pii_shield import redact

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
SAMPLES_DIR = REPO_ROOT / "data" / "samples"
SYNTHETIC_DIR = REPO_ROOT / "data" / "synthetic" / "meetings"

# The two speaker shapes these fixtures actually use, and both are needed.
#
# `Word:` at the start of a line covers `.txt` and `.srt`, where the cue text sits on its own
# line; the timestamp lines cannot match because they open with a digit. `.vtt` does not use it
# at all -- it carries the speaker in a WebVTT voice tag, `<v Lucas>text`, and a first version
# of this file matched only the colon form and found NO speaker in any of the three `.vtt`
# fixtures. They would have been parametrised, iterated and asserted about nothing.
_SPEAKER_LABEL_RE = re.compile(r"(?m)^([A-ZÀ-Ú][a-zà-ú]+):|<v\s+([A-ZÀ-Ú][a-zà-ú]+)\s*>")

# Line openers that are document furniture rather than people. They are on
# `_COMMON_PHRASE_HEADS`, except `Horário`, which is simply not a given name -- and neither
# fact is what this set is for: it is here so that the "unrecognised speaker" record below
# holds speakers and not headings.
_TRANSCRIPT_HEADERS = frozenset({"Reunião", "Data", "Participantes", "Horário"})

# The speakers in these fixtures whose given name is on neither of the shield's name lists.
#
# ONE, and it is the documented residual rather than a defect of this file: `_BR_TOP_NAMES` is a
# frequency table of 271 given names and Brazil has rather more. `test_pii_corpus.py` is where
# the rate is held and dated; this record exists so that adding a transcript with an
# unrecognised speaker is a decision somebody makes.
#
# Deliberately NOT asserted to leak. Whether a given name off the list survives depends on what
# is beside it -- a full name is caught by the sequence pattern, a bare label is not -- and
# pinning "this one leaks" would pin the accident of one fixture's punctuation.
_SPEAKERS_THE_SHIELD_DOES_NOT_KNOW = frozenset({"Luísa"})

_PLACEHOLDER_RE = re.compile(r"\[\[[A-Z_]+_\d+\]\]")


def _transcripts() -> list[pathlib.Path]:
    paths = [
        p
        for directory in (SAMPLES_DIR, SYNTHETIC_DIR)
        for p in sorted(directory.iterdir())
        if p.suffix in {".txt", ".srt", ".vtt"}
    ]
    return paths


_TRANSCRIPTS = _transcripts()


def _speaker_labels(text: str) -> set[str]:
    labels = {m.group(1) or m.group(2) for m in _SPEAKER_LABEL_RE.finditer(text)}
    return labels - _TRANSCRIPT_HEADERS


def test_the_fixture_directories_are_where_this_file_thinks_they_are() -> None:
    """A path that stopped resolving would make every test below pass by iterating nothing.

    The count is a floor rather than an equality: transcripts get added, and this file should
    notice a directory that vanished, not a directory that grew.
    """
    assert SAMPLES_DIR.is_dir(), SAMPLES_DIR
    assert SYNTHETIC_DIR.is_dir(), SYNTHETIC_DIR
    assert len(_TRANSCRIPTS) >= 20, f"{len(_TRANSCRIPTS)} transcripts found under {REPO_ROOT}"


@pytest.mark.parametrize("path", _TRANSCRIPTS, ids=lambda p: p.name)
def test_a_recognised_speaker_never_survives_a_whole_transcript(path: pathlib.Path) -> None:
    """Every occurrence, not the first one, and over the document rather than over a sentence.

    Case-sensitive on purpose. The shield claims a proper name in Title Case, so the question is
    whether the NAME is gone, and a fold-insensitive check would report `lia` in "ele lia o
    relatorio" as a leak of the speaker `Lia`.
    """
    text = path.read_text(encoding="utf-8")
    result = redact(text)
    visible = _PLACEHOLDER_RE.sub(" ", result.redacted_text)

    known = sorted(
        label
        for label in _speaker_labels(text)
        if pii_shield._fold(label) in pii_shield._BR_TOP_NAMES
    )
    assert known, f"{path.name} has no speaker the shield's lists recognise; nothing is measured"

    survivors = [
        label for label in known if re.search(rf"(?<!\w){re.escape(label)}(?!\w)", visible)
    ]
    assert not survivors, (
        f"{path.name}: {survivors} reached the output of the shield.\n"
        "  These are speaker names on `_BR_TOP_NAMES`, so the lists are not the reason -- a "
        "pattern that used to reach them stopped. Read the redacted text around the surviving "
        "occurrence rather than the name: it is the CONTEXT that changed.\n"
        f"  {result.redacted_text[:400]!r}"
    )


@pytest.mark.parametrize("path", _TRANSCRIPTS, ids=lambda p: p.name)
def test_the_shield_actually_ran_on_every_transcript(path: pathlib.Path) -> None:
    """The counter, which is what `/analyze` reports to the backend as its audit trail.

    A shield that silently stopped matching would leave every assertion about ABSENCE above
    satisfied only if the names also vanished, so this is the half that fails loudly instead.
    """
    result = redact(path.read_text(encoding="utf-8"))
    assert result.redactions, f"{path.name}: the shield found nothing in a transcript full of names"


def test_the_unrecognised_speakers_are_the_recorded_ones() -> None:
    """The residual, named. Adding a transcript with a new off-list speaker fails here.

    This is not a leak assertion -- see the note above `_SPEAKERS_THE_SHIELD_DOES_NOT_KNOW`. It
    is the record that says how much of these fixtures the test above is NOT covering, so the
    coverage cannot quietly shrink while the file keeps reporting green.
    """
    unknown = set()
    for path in _TRANSCRIPTS:
        for label in _speaker_labels(path.read_text(encoding="utf-8")):
            if pii_shield._fold(label) not in pii_shield._BR_TOP_NAMES:
                unknown.add(label)

    assert unknown == set(_SPEAKERS_THE_SHIELD_DOES_NOT_KNOW), (
        f"the set of speakers the shield's lists do not recognise moved.\n"
        f"  added:   {sorted(unknown - _SPEAKERS_THE_SHIELD_DOES_NOT_KNOW)}\n"
        f"  removed: {sorted(_SPEAKERS_THE_SHIELD_DOES_NOT_KNOW - unknown)}\n\n"
        "A name ADDED here is a speaker the test above does not cover. A name REMOVED is a name "
        "that reached `_BR_TOP_NAMES`, which is good and should be recorded as such."
    )
