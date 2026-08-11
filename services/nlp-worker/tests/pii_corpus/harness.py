"""Runs the corpus through the shield and reports both rates.

    python -m tests.pii_corpus.harness

from `services/nlp-worker`. The same entry point backs `tests/test_pii_corpus.py`, so the number
in CI and the number on a workstation come from one implementation.

The two rates are computed independently and printed together, and `render()` refuses to omit
either -- a report showing only the leak rate is how a redaction defect gets closed by redacting
the whole transcript.
"""

from __future__ import annotations

import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from nora_nlp.services.pii_shield import redact

from .cases import KNOWN_GAP, Case, all_cases

# Any `[[TYPE_N]]` the shield emits. Removed before looking for surviving tokens, so a
# placeholder's own words can never be mistaken for the text they replaced.
_PLACEHOLDER_RE = re.compile(r"\[\[[A-Z_]+_\d+\]\]")

_PERSON_PLACEHOLDER_RE = re.compile(r"\[\[PERSON_NAME_\d+\]\]")


def _fold(value: str) -> str:
    """Case- and accent-insensitive comparison form.

    Deliberately re-implemented rather than imported from `pii_shield`: an oracle that shares the
    normalisation of the thing it measures cannot see a defect in that normalisation.
    """
    decomposed = unicodedata.normalize("NFKD", value)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


def _contains_token(haystack_folded: str, token: str) -> bool:
    return re.search(rf"(?<!\w){re.escape(_fold(token))}(?!\w)", haystack_folded) is not None


@dataclass(frozen=True)
class CaseResult:
    case: Case
    output: str
    leaked: tuple[str, ...]
    over_redacted: tuple[str, ...]

    @property
    def is_leak(self) -> bool:
        return bool(self.leaked)

    @property
    def is_over_redaction(self) -> bool:
        return bool(self.over_redacted)

    @property
    def ok(self) -> bool:
        return not self.leaked and not self.over_redacted

    def describe(self) -> str:
        parts = []
        if self.leaked:
            parts.append(f"leaked {list(self.leaked)}")
        if self.over_redacted:
            parts.append(f"over-redacted {list(self.over_redacted)}")
        detail = "; ".join(parts) or "ok"
        return f"[{self.case.case_id}] {self.case.text!r} -> {self.output!r}  ({detail})"


def evaluate(case: Case) -> CaseResult:
    output = redact(case.text).redacted_text
    visible = _fold(_PLACEHOLDER_RE.sub(" ", output))

    leaked = tuple(t for t in case.must_vanish if _contains_token(visible, t))

    over = [t for t in case.must_survive if not _contains_token(visible, t)]
    # A string with nobody in it must not produce a PERSON_NAME at all. Without this, a case
    # whose surviving tokens happen to sit outside the redacted span would score clean while a
    # person who does not exist was invented next to them.
    if case.expects_no_person and _PERSON_PLACEHOLDER_RE.search(output):
        over.append("<invented PERSON_NAME>")

    return CaseResult(case=case, output=output, leaked=leaked, over_redacted=tuple(over))


@dataclass(frozen=True)
class Rate:
    failed: int
    total: int
    documented: int

    @property
    def value(self) -> float:
        return 0.0 if self.total == 0 else self.failed / self.total

    @property
    def undocumented(self) -> int:
        return self.failed - self.documented

    def render(self, label: str) -> str:
        pct = f"{self.value * 100:6.2f}%"
        line = f"{label:<22}: {pct}  ({self.failed} / {self.total} cases)"
        if self.documented:
            line += f"  of which documented gaps: {self.documented}"
        return line


@dataclass(frozen=True)
class Report:
    results: list[CaseResult]

    @property
    def leak(self) -> Rate:
        scope = [r for r in self.results if r.case.must_vanish]
        failed = [r for r in scope if r.is_leak]
        return Rate(
            failed=len(failed),
            total=len(scope),
            documented=len([r for r in failed if r.case.status == KNOWN_GAP]),
        )

    @property
    def false_redaction(self) -> Rate:
        scope = [r for r in self.results if r.case.must_survive or r.case.expects_no_person]
        failed = [r for r in scope if r.is_over_redaction]
        return Rate(
            failed=len(failed),
            total=len(scope),
            documented=len([r for r in failed if r.case.status == KNOWN_GAP]),
        )

    def by_shape(self) -> list[tuple[str, Rate, Rate]]:
        shapes: dict[str, list[CaseResult]] = defaultdict(list)
        for r in self.results:
            shapes[r.case.shape].append(r)
        rows = []
        for shape in sorted(shapes):
            rows.append((shape, Report(shapes[shape]).leak, Report(shapes[shape]).false_redaction))
        return rows

    def failures(self) -> list[CaseResult]:
        return [r for r in self.results if not r.ok]

    def render(self, examples: int = 12) -> str:
        leak, false_redaction = self.leak, self.false_redaction
        lines = [
            "PII SHIELD CORPUS",
            f"{len(self.results)} cases",
            "",
            # Both, always. Neither line is meaningful without the other: driving the first to
            # zero by redacting every Title Case token drives the second through the roof.
            leak.render("leak rate"),
            false_redaction.render("false-redaction rate"),
            "",
            f"{'shape':<26}{'leak':>16}{'false redaction':>22}",
        ]
        for shape, srate, frate in self.by_shape():
            lines.append(
                f"{shape:<26}"
                f"{srate.failed:>6}/{srate.total:<5}{srate.value * 100:>5.1f}%"
                f"{frate.failed:>10}/{frate.total:<5}{frate.value * 100:>5.1f}%"
            )
        undocumented = [r for r in self.failures() if r.case.status != KNOWN_GAP]
        lines += ["", f"undocumented failures: {len(undocumented)}"]
        for r in undocumented[:examples]:
            lines.append("  " + r.describe())
        if len(undocumented) > examples:
            lines.append(f"  ... and {len(undocumented) - examples} more")
        gaps = sorted(
            {r.case.note for r in self.failures() if r.case.status == KNOWN_GAP if r.case.note}
        )
        if gaps:
            lines += ["", "documented gaps still open:"]
            lines += [f"  - {g}" for g in gaps]
        return "\n".join(lines)


def run(cases: list[Case] | None = None) -> Report:
    return Report([evaluate(c) for c in (cases if cases is not None else all_cases())])


def main() -> int:
    report = run()
    print(report.render())
    return 0


if __name__ == "__main__":
    sys.exit(main())
