#!/usr/bin/env bash
# Fails when a pull request title is not a usable commit subject.
#
# WHY THIS EXISTS, and it is not hypothetical. `main` is merged with squash, and
# GitHub takes the squash commit's SUBJECT from the PULL REQUEST TITLE, not from
# any commit on the branch. So the PR title is the only text of a merge that is
# never reviewed as code and never checked by anything — and on 2026-08-16 that
# produced commit `3696e55`, whose subject is in Portuguese, on a repository whose
# rule is that commit messages are English. It could not be corrected: `main`
# carries `non_fast_forward` and `required_linear_history`.
#
# `scripts/check-language.sh` was no help and never could be: it scans file names
# and file contents. A commit message is neither. This is the gap it leaves.
#
# Checked, in order of how much each has actually cost:
#   (a) Portuguese — the failure that happened. Same two signals as
#       check-language.sh: pt-specific accented characters, plus a curated word
#       list that excludes words which are also English.
#   (b) Conventional Commits shape — `type(scope): subject`, because the subject
#       becomes a commit subject and the repo's convention is Conventional
#       Commits.
#   (c) Length — set from THIS repository's history, not from the usual advice.
#       The conventional 72 was tried first and rejected by measurement: across
#       413 commits the median subject is exactly 72 characters, so that limit
#       would reject half of everything ever merged here, including subjects
#       that are good. A guard that fails established practice on every other
#       commit is not a guard, it is something people learn to route around.
#       The distribution is median 72, p90 92, p99 117, max 132. The limit is
#       120: above p99, so it never argues with a well-written subject, and low
#       enough to catch the runaway paragraph-as-title.
#
# Deliberately NOT checked: the PR body. It becomes the commit body, but it is
# reviewed in the same place it is written, and a rule over prose that long
# produces false positives — which is the one thing that makes a guard get
# ignored.
#
# Usage: check-pr-title.sh "<title>"    (CI passes ${{ github.event.pull_request.title }})
#        check-pr-title.sh              (reads PR_TITLE from the environment)
set -euo pipefail

TITLE="${1:-${PR_TITLE:-}}"

if [[ -z "$TITLE" ]]; then
  echo "check-pr-title: no title given (pass it as \$1 or set PR_TITLE)" >&2
  exit 2
fi

fail() {
  echo ""
  echo "  PR title: $TITLE"
  echo ""
  echo "  This title becomes the SUBJECT of the squash commit on main, permanently."
  echo "  main carries non_fast_forward, so it cannot be corrected after the merge."
  echo ""
  exit 1
}

# ---------------------------------------------------------------------------
# (a) Portuguese. The failure this file exists for.
# ---------------------------------------------------------------------------
# Accented characters that appear in Portuguese and effectively never in an
# English commit subject. `ç` and the tildes carry almost all the signal.
#
# WRITTEN AS CODEPOINTS, and that is not pedantry — the literal bracket class
# `[ãõá...ÔÚçÇ]` is a live bug. Without `(*UTF8)`, PCRE reads the class BYTE by
# byte, so it holds every byte of every character in it: `Ô` is C3 94, and an em
# dash is E2 80 94. They share the byte 94, so the class matched every title
# containing `—` and this repository uses em dashes constantly. Calibrating
# against all 413 real subjects flagged sixteen of them as Portuguese; every one
# was an em dash. Verified after the fix against —, –, ’, … and →, all clean,
# while real pt-BR still matches in both cases.
PT_ACCENTS='(*UTF8)[\x{00E3}\x{00F5}\x{00E1}\x{00E2}\x{00E9}\x{00EA}\x{00ED}\x{00F3}\x{00F4}\x{00FA}\x{00E7}\x{00C3}\x{00D5}\x{00C1}\x{00C2}\x{00C9}\x{00CA}\x{00CD}\x{00D3}\x{00D4}\x{00DA}\x{00C7}]'
if printf '%s' "$TITLE" | grep -qP "$PT_ACCENTS"; then
  echo "ERROR: the PR title contains Portuguese accented characters."
  fail
fi

# TWO signals, because one is not enough and this repository has the receipt.
#
# The title that caused all of this — "fix(admin): console de operador
# fail-closed e dentro do ci-gate (B10)" — has no accented character and no
# distinctive verb. A single curated word list passes it, which is EXACTLY the
# blind spot check-language.sh is documented to have. So:
#
#   STRONG words fail on their own. Unambiguously Portuguese nouns and verb
#   stems that no English commit subject contains.
#
#   WEAK markers fail only in PAIRS. These are Portuguese function words, each
#   of which could appear innocently in English ("de facto", an "e" in a list),
#   but two of them in one short subject is Portuguese sentence structure, not
#   coincidence. All are matched space-delimited, so "de-duplicate", "e-mail"
#   and "com.fasterxml" do not count.
#
# Both lists exclude words that are also English ("no", "os", "as", "com",
# "ate", "pro"), for the reason check-language.sh gives: a false positive costs
# more than a missed word, because a guard people distrust gets bypassed.
# Every stem below was calibrated against all 38 real commit subjects on `main`,
# and three had to be corrected because they matched ENGLISH: `remov` matched
# "remove" and "removals", `implement(a|ando)` matched "implementation", and
# `deleta` matched "deletable". Portuguese stems that are prefixes of English
# words are useless here — they are spelled out fully instead.
PT_STRONG='corrig|adicion|removid|removend|atualiz|melhor|implementa(r|ndo|cao)|ajust|arrum|cria(r|ndo)|deletar|deletad|refator|mudanc|configurac|permiss(a|oes)|usuario|operador|reuniao|reunioes|arquivo|tarefa|senha|servidor|dentro|banco de dados|nao |que o |que a '
if printf '%s' "$TITLE" | grep -qiP "\\b($PT_STRONG)"; then
  echo "ERROR: the PR title looks like Portuguese."
  echo "       Matched: $(printf '%s' "$TITLE" | grep -oiP "\\b($PT_STRONG)" | head -3 | tr '\n' ' ')"
  fail
fi

# `|| true` is load-bearing, not defensive noise. Under `set -o pipefail` a grep
# that matches NOTHING exits 1, which fails the command substitution, which
# `set -e` turns into an exit — so without it this guard aborts on exactly the
# titles it should pass, and the CI job goes red for every correct English title.
# It did, on the first run, for 37 of the 38 real subjects.
PT_WEAK=' de | e | do | da | dos | das | nos | nas | para | pelo | pela | ao | aos '
WEAK_HITS=$(printf ' %s ' "$TITLE" | grep -oiP "$PT_WEAK" | wc -l || true)
if [[ "$WEAK_HITS" -ge 2 ]]; then
  echo "ERROR: the PR title has $WEAK_HITS Portuguese function words — that is sentence"
  echo "       structure, not coincidence."
  echo "       Matched: $(printf ' %s ' "$TITLE" | grep -oiP "$PT_WEAK" | sort -u | tr -d '\n')"
  fail
fi

# ---------------------------------------------------------------------------
# (b) Conventional Commits shape.
# ---------------------------------------------------------------------------
# type(optional scope)(optional !): subject
# Types are the set this repository actually uses; `git log` on main is the source.
TYPES='build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test'
if ! printf '%s' "$TITLE" | grep -qP "^($TYPES)(\([a-z0-9,._/-]+\))?!?: .+"; then
  echo "ERROR: the PR title is not a Conventional Commit."
  echo "       Expected: <type>(<optional scope>): <subject>"
  echo "       Types:    $(printf '%s' "$TYPES" | tr '|' ' ')"
  fail
fi

# A subject that is only the type is technically well-formed and says nothing.
SUBJECT="${TITLE#*: }"
if [[ ${#SUBJECT} -lt 10 ]]; then
  echo "ERROR: the subject after the colon is ${#SUBJECT} characters. Say what changed."
  fail
fi

# ---------------------------------------------------------------------------
# (c) Length.
# ---------------------------------------------------------------------------
# Counted in CHARACTERS, not bytes. An em dash is 3 bytes in UTF-8, and `${#VAR}`
# in bash counts characters when a UTF-8 locale is set — which CI does not
# guarantee, so normalise it here rather than trust the runner.
LEN=$(LC_ALL=C.UTF-8 printf '%s' "$TITLE" | LC_ALL=C.UTF-8 awk '{print length($0)}')
if [[ "$LEN" -gt 120 ]]; then
  echo "ERROR: the PR title is $LEN characters; the limit is 120."
  echo "       That limit is this repository's own p99 rounded up, not the usual 72 —"
  echo "       which is the MEDIAN here and would reject half of the history."
  echo "       At this length the subject is carrying what the PR body is for."
  fail
fi

echo "OK: PR title is a usable commit subject ($LEN chars)."
