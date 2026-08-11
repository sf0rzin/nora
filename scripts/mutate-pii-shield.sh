#!/usr/bin/env bash
# Mutation testing for the PII shield's arbitration rules.
#
# Breaks each rule on purpose and asserts the suite notices. Run from the repository root:
#
#     bash scripts/mutate-pii-shield.sh
#
# It needs a working `services/nlp-worker` environment (`pip install -e ".[dev]"`); it uses
# `python -m pytest` from whatever interpreter is on PATH, or `$PYTHON` if set.
#
# WHY THIS IS IN THE REPOSITORY. `pii_shield.py` is the last gate before every provider call
# (ADR 0012), and its tests are the only thing standing between a refactor and a leak. Three
# separate reviews of one change found defects that the suite passed cleanly over, so "565 tests
# pass" is not evidence about the arbitration. This is. Keeping it here means the claim in a
# commit message can be re-derived by whoever reads it instead of taken on trust.
#
# TWO PROPERTIES, and the second is the one people forget:
#   - every REAL mutation must be caught. A survivor means the rule is not load-bearing.
#   - every INERT mutation must survive. A harness that reports failure whatever it does is
#     reassurance, not evidence, and this one did exactly that for three rules until a negative
#     control was added.
# A patch that fails to apply is reported LOUDLY, because a stale pattern prints "0 failures",
# which reads as "the tests are weak" when it means "the harness is out of date". That happened
# twice while this file was being written.

set -uo pipefail

# Resolved BEFORE the cd, so a repo-relative `PYTHON=services/nlp-worker/.venv/bin/python` works
# the way anyone would expect it to.
PYTHON="${PYTHON:-python}"
case "$PYTHON" in
  /*) ;;
  */*) PYTHON="$(cd "$(dirname "$PYTHON")" && pwd)/$(basename "$PYTHON")" ;;
esac
cd "$(git rev-parse --show-toplevel)/services/nlp-worker" || exit 1
command -v "$PYTHON" >/dev/null 2>&1 || { echo "no interpreter at '$PYTHON' — set \$PYTHON"; exit 1; }
SHIELD=src/nora_nlp/services/pii_shield.py
ORIG=$(mktemp)
cp "$SHIELD" "$ORIG"
trap 'cp "$ORIG" "$SHIELD"; rm -f "$ORIG"' EXIT

STALE=0
SURVIVED=0

mutate() {  # mutate <label> <old> <new> <catch|survive>
  "$PYTHON" - "$2" "$3" <<'PY'
import pathlib, sys
old, new = sys.argv[1], sys.argv[2]
p = pathlib.Path("src/nora_nlp/services/pii_shield.py")
s = p.read_text(encoding="utf-8")
if old not in s:
    sys.exit(2)
p.write_text(s.replace(old, new, 1), encoding="utf-8")
PY
  rc=$?
  # 2 is "the pattern is not in the file"; anything else is the interpreter failing, and the
  # two must not be conflated — the first run of this script reported thirteen stale patches
  # when the real cause was a bad $PYTHON path.
  if [ "$rc" -eq 2 ]; then
    printf '%-64s  *** PATCH DID NOT APPLY - harness is stale ***\n' "$1"
    STALE=$((STALE + 1)); cp "$ORIG" "$SHIELD"; return
  elif [ "$rc" -ne 0 ]; then
    printf '%-64s  *** could not patch (python exit %s) ***\n' "$1" "$rc"
    STALE=$((STALE + 1)); cp "$ORIG" "$SHIELD"; return
  fi
  n=$("$PYTHON" -m pytest -q tests/ 2>&1 | grep -cE '^FAILED')
  if [ "$4" = "catch" ]; then
    if [ "$n" -gt 0 ]; then printf '%-64s  %3d failures  ok\n' "$1" "$n"
    else printf '%-64s  %3d failures  *** SURVIVED ***\n' "$1" "$n"; SURVIVED=$((SURVIVED + 1)); fi
  else
    if [ "$n" -eq 0 ]; then printf '%-64s  %3d failures  ok (inert, as required)\n' "$1" "$n"
    else printf '%-64s  %3d failures  *** FALSE POSITIVE ***\n' "$1" "$n"; SURVIVED=$((SURVIVED + 1)); fi
  fi
  cp "$ORIG" "$SHIELD"
}

SPLIT='    runs: list[list[re.Match[str]]] = []
    current: list[re.Match[str]] = []
    for tok in tokens:'
SUBTRACT='    if _fold(run[-1].group(0)) in _COMPANY_TAIL_WORDS and len(_name_bearing(run[:-1])) >= 2:'
ROLE='    if all(
        _fold(t.group(0)) in _NAME_HONORIFICS
        or _fold(t.group(0)) in _NAME_CONNECTIVES
        or _fold(t.group(0)) in _COMMON_PHRASE_HEADS
        or _fold(t.group(0)) in _COMPANY_TAIL_WORDS
        for t in run
    ):'

echo "=== REAL MUTATIONS (must be caught) ==="

mutate "M1  no arbitration at all" \
  "$SPLIT" "    return [tokens]  # M1
$SPLIT" catch

mutate "M2  allow-listed token discards the whole candidate (pre-fix rule)" \
  "$SPLIT" "    if any(_fold(t.group(0)) in _PERSON_NAME_NEGATIVE_LIST for t in tokens):
        return []  # M2
$SPLIT" catch

mutate "M3  corporate suffix no longer subtracted" \
  "$SUBTRACT" '    while False and _fold(run[-1].group(0)) in _COMPANY_TAIL_WORDS:  # M3' catch

mutate "M4  subtraction collapses a two-token run again" \
  "$SUBTRACT" '    while _fold(run[-1].group(0)) in _COMPANY_TAIL_WORDS and len(run) > 1:  # M4' catch

# There is no "the subtraction stops iterating" mutation, because the subtraction does not
# iterate: it is an `if`. Every span returned is re-qualified by `_qualify_and_claim` over the
# narrowed slice, which calls back into `_trusted_span`, so a second corporate word is removed
# on the next pass. It WAS a `while`, and this harness is what showed the loop body's second
# iteration was exercised by nothing at all.

mutate "M6  _name_bearing also excludes corporate words" \
  '        if _fold(t.group(0)) not in _NAME_CONNECTIVES
        and _fold(t.group(0)) not in _COMMON_PHRASE_HEADS' \
  '        if _fold(t.group(0)) not in _NAME_CONNECTIVES
        and _fold(t.group(0)) not in _COMMON_PHRASE_HEADS
        and _fold(t.group(0)) not in _COMPANY_TAIL_WORDS  # M6' catch

# Nor is there an "a run of only corporate words is a trading name" mutation. That rule was
# written, and this harness showed it survives deletion: `_COMPANY_TAIL_WORDS` is a subset of
# the union the role refusal tests, so `all(corporate)` already implies `all(union)`. It was
# removed rather than pinned — a control that cannot fire is not made real by a test for it.

mutate "M8  role refusal removed" \
  "$ROLE" '    if False:  # M8' catch

mutate "M9  role refusal stops counting corporate words" \
  '        or _fold(t.group(0)) in _COMPANY_TAIL_WORDS
        for t in run
    ):' \
  '        for t in run
    ):  # M9' catch

mutate "M10 a person-only honorific vouches for anything again" \
  '        return any(
            _fold(t.group(0)) not in _NAME_HONORIFICS' \
  '        return True  # M10
        return any(
            _fold(t.group(0)) not in _NAME_HONORIFICS' catch

mutate "M11 all-caps path stops splitting on the allow list" \
  '    for run in _split_on_allow_list(list(_WORD_RE.finditer(match.group(0)))):' \
  '    for run in [list(_WORD_RE.finditer(match.group(0)))]:  # M11' catch

echo
echo "=== NEGATIVE CONTROLS (must survive) ==="

mutate "C1  a comment changes and nothing else" \
  '    ARBITRATION. This is the one place where an allow-listed term meets a name candidate' \
  '    ARBITRATION (C1). This is the one place an allow-listed term is met by a candidate' survive

mutate "C2  two independent assignments reordered" \
  '    runs: list[list[re.Match[str]]] = []
    current: list[re.Match[str]] = []' \
  '    current: list[re.Match[str]] = []
    runs: list[list[re.Match[str]]] = []' survive

cp "$ORIG" "$SHIELD"
echo
echo "stale patches : $STALE"
echo "bad outcomes  : $SURVIVED"
"$PYTHON" -m pytest -q tests/ >/dev/null 2>&1 && echo "clean suite   : green" || echo "clean suite   : RED"
[ "$STALE" -eq 0 ] && [ "$SURVIVED" -eq 0 ]
