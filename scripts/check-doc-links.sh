#!/usr/bin/env bash
# Verifies that the repository's two ways of pointing at a file both resolve.
#
# PASS 1 — RELATIVE MARKDOWN LINKS.
# Scope: every tracked `*.md` file. Two link shapes are collected:
#   - inline links and images:  [text](target)  /  ![alt](target)
#   - reference definitions:    [label]: target
#
# The target is resolved against the directory of the file that contains it.
# Skipped: absolute URLs (http:, https:, mailto:, tel:, ftp:), protocol-relative
# URLs (//host/...) and bare anchors (#section). A trailing `#anchor` is stripped
# before the existence check, so `../adr/README.md#index` is checked as
# `../adr/README.md`.
#
# PASS 2 — `path/to/file:LINE` CITATIONS INSIDE CODE SPANS.
# Pass 1 deletes code spans before looking for links, and that is right: a document showing
# `[label](path)` as an EXAMPLE documents the syntax rather than linking anywhere. But it
# meant this guard was blind to the format this repository actually uses to cite evidence —
# `` `infra/host/docker-compose.yml:718` `` — in ADRs, the backlog and every audit note. So a
# renamed file left dozens of citations pointing nowhere and CI said "every relative markdown
# link resolves", which was true and beside the point.
#
# This pass therefore reads the SPANS pass 1 throws away, and only the ones that are
# unambiguous about what they mean:
#   - the whole span is `<path>:<line>` or `<path>:<line>-<line>` and nothing else;
#   - the path has at least one `/` and its first segment is a real top-level directory of
#     this repository (derived from `git ls-files`, so it needs no maintenance here);
#   - no `...` in it — `services/api/.../AuthController.java:97` is a deliberate elision, not
#     a path, and demanding it resolve would only teach people to stop citing.
# For each survivor: the file must exist and the cited line must be inside it. An out-of-range
# line is reported as loudly as a missing file — a citation surviving a rewrite of the file it
# points at is the failure mode that leaves prose describing code that is no longer there.
#
# WHAT IS STILL NOT CHECKED, so the guard is not read as more than it is: prose references
# with no syntax at all ("architecture.md §19"), external http(s) URLs, and `#anchor`
# fragments — the last one because there is currently not a single relative markdown link in
# the repository carrying an anchor, and a rule with no subject is a rule nobody maintains.
#
# Exits non-zero and lists every `file:line` whose target does not resolve.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

failures=0

# ---------------------------------------------------------------------------
# Deliberately dead links, as exact `<file>|<target>` pairs.
#
# An accepted ADR is immutable, so an ADR that pointed at a document a LATER decision
# deleted or renamed cannot be repaired without rewriting the record of what was decided.
# The link stays dead and is declared here, naming the successor that killed it.
#
# Pairs, not filenames, on purpose: every OTHER link in the same file is still checked, so
# this cannot quietly grow into "skip that document".
# ---------------------------------------------------------------------------
DEAD_ON_PURPOSE=(
  # ADR 0036 renamed the runbook to host-deploy.md and deleted the Azure decommission
  # runbook (the subscription is gone; there is nothing left to shut down).
  "docs/adr/0034-azure-to-proxmox-migration.md|../operations/proxmox-deploy.md"
  "docs/adr/0034-azure-to-proxmox-migration.md|../operations/azure-decommission.md"
)

is_dead_on_purpose() {
  local pair="$1|$2" entry
  for entry in "${DEAD_ON_PURPOSE[@]}"; do
    [ "$entry" = "$pair" ] && return 0
  done
  return 1
}

# Same idea, second pass. Separate array because the target here is REPO-ROOT-relative while
# the one above is relative to the citing file — one list holding both meanings is how a
# declaration ends up excusing the wrong thing.
#
# ADR 0034 is accepted, and an accepted ADR records what was decided at the time: it cites
# two files that ADR 0036 later deleted along with the entire Azure substrate. Repairing the
# citation would rewrite the record.
CITATIONS_DEAD_ON_PURPOSE=(
  "docs/adr/0034-azure-to-proxmox-migration.md|infra/bicep/main.dev.bicepparam"
  "docs/adr/0034-azure-to-proxmox-migration.md|docs/operations/azure-deploy.md"
)

is_citation_dead_on_purpose() {
  local pair="$1|$2" entry
  for entry in "${CITATIONS_DEAD_ON_PURPOSE[@]}"; do
    [ "$entry" = "$pair" ] && return 0
  done
  return 1
}

# True when the target is not a repo-relative path we can check on disk.
is_external() {
  case "$1" in
    http://* | https://* | mailto:* | tel:* | ftp://* | //* | '#'* | '') return 0 ;;
    *) return 1 ;;
  esac
}

# check_link <file> <line> <raw-target>
check_link() {
  local file=$1 line=$2 raw=$3
  local target=$raw

  # `[text](<path with spaces>)` — angle-bracket form.
  target=${target#<}
  target=${target%>}

  # `[text](path "Optional title")` — drop the title.
  target=${target%% *}

  # `path#section` — drop the anchor.
  target=${target%%#*}

  # The only escape that shows up in practice.
  target=${target//%20/ }

  if is_external "$target"; then
    return 0
  fi

  local dir
  dir=$(dirname "$file")

  if [ ! -e "$dir/$target" ]; then
    if is_dead_on_purpose "$file" "$target"; then
      return 0
    fi
    printf '%s:%s: broken relative link -> %s\n' "$file" "$line" "$raw"
    failures=$((failures + 1))
  fi
}

# Tracked markdown, minus anything not on disk. A tracked file with no working copy cannot
# happen in CI — the checkout is complete — but it happens constantly on a workstation, in the
# middle of a rename or a deletion that has not been committed yet. Both passes below feed
# these names straight to awk, and awk treats a missing input as FATAL, so without this filter
# a half-finished rename anywhere in the repository takes the whole guard down with an error
# about a file the author was in the process of deleting on purpose.
files=()
while IFS= read -r f; do
  [ -f "$f" ] || continue
  files+=("$f")
done < <(git ls-files '*.md')

# Removes the parts of a markdown file that only LOOK like links:
#   - fenced code blocks (``` or ~~~) — a shell snippet such as
#     `[Convert]::ToBase64String(...)` is not a link;
#   - inline code spans — a doc that shows `[label](path)` as an EXAMPLE is
#     documenting the syntax, not linking anywhere.
# Line numbering is preserved so the reported positions stay accurate.
# Reads the file named by $1 and writes the cleaned body to stdout.
strip_non_link_text() {
  awk '
    /^[[:space:]]*(```|~~~)/ { fence = !fence; print ""; next }
    fence                    { print ""; next }
                             { gsub(/`[^`]*`/, " "); print }
  ' "$1"
}

for file in "${files[@]}"; do
  body=$(strip_non_link_text "$file")

  # Inline links. `grep -o` emits one match per line, each prefixed with its line
  # number, so a line holding several links produces several records.
  while IFS= read -r record; do
    [ -n "$record" ] || continue
    lineno=${record%%:*}
    match=${record#*:}
    match=${match#*\](}
    match=${match%)}
    check_link "$file" "$lineno" "$match"
  done < <(printf '%s\n' "$body" | grep -noE '\]\([^)]*\)' || true)

  # Reference definitions: `[label]: target`.
  while IFS= read -r record; do
    [ -n "$record" ] || continue
    lineno=${record%%:*}
    match=${record#*:}
    match=${match#*]:}
    # Trim the leading whitespace left by `]:`.
    match=${match#"${match%%[![:space:]]*}"}
    check_link "$file" "$lineno" "$match"
  done < <(printf '%s\n' "$body" | grep -noE '^[[:space:]]*\[[^]]+\]:[[:space:]]*[^[:space:]]+' || true)
done

# ---------------------------------------------------------------------------
# PASS 2 — `path:line` citations inside code spans.
# ---------------------------------------------------------------------------
# The set of first path segments that name a directory in this repository. Derived rather
# than listed: a new top-level directory joins the check on its own, and a deleted one stops
# producing false positives without anybody remembering this file exists.
declare -A REPO_ROOTS=()
while IFS= read -r tracked; do
  case "$tracked" in
    */*) REPO_ROOTS["${tracked%%/*}"]=1 ;;
  esac
done < <(git ls-files)

# One awk pass over every markdown file, emitting `file<TAB>line<TAB>span` for each inline
# code span that is exactly a `path:line` (or `path:line-line`) citation. Fenced blocks are
# skipped for the same reason pass 1 skips them: a shell snippet is not a claim about a file.
citations_checked=0
while IFS=$'\t' read -r file lineno span; do
  [ -n "$span" ] || continue

  target=${span%%:*}
  lines=${span#*:}
  start=${lines%%-*}
  end=${lines##*-}

  case "$target" in
    */*) ;;
    *) continue ;;
  esac
  [ -n "${REPO_ROOTS[${target%%/*}]:-}" ] || continue

  citations_checked=$((citations_checked + 1))

  if [ ! -f "$target" ]; then
    is_citation_dead_on_purpose "$file" "$target" && continue
    printf '%s:%s: citation points at a file that does not exist -> %s\n' "$file" "$lineno" "$span"
    failures=$((failures + 1))
    continue
  fi

  total=$(awk 'END { print NR }' "$target")
  if [ "$start" -gt "$total" ] || [ "$end" -gt "$total" ]; then
    printf '%s:%s: citation is past the end of the file -> %s (%s has %s lines)\n' \
      "$file" "$lineno" "$span" "$target" "$total"
    failures=$((failures + 1))
  fi
done < <(
  awk '
    FNR == 1 { fence = 0 }
    /^[[:space:]]*(```|~~~)/ { fence = !fence; next }
    fence { next }
    {
      rest = $0
      while (match(rest, /`[^`]+`/)) {
        span = substr(rest, RSTART + 1, RLENGTH - 2)
        rest = substr(rest, RSTART + RLENGTH)
        if (span ~ /\.\.\./) continue
        if (span ~ /^[A-Za-z0-9._\/-]+:[0-9]+(-[0-9]+)?$/)
          printf "%s\t%d\t%s\n", FILENAME, FNR, span
      }
    }
  ' "${files[@]}"
)

if [ "$failures" -gt 0 ]; then
  printf '\n%d unresolved reference(s) across %d markdown file(s).\n' "$failures" "${#files[@]}" >&2
  exit 1
fi

printf 'OK — every relative markdown link resolves (%d declared dead on purpose) and every one of\n' \
  "${#DEAD_ON_PURPOSE[@]}"
printf '   the %d `path:line` citations points at a real file and a real line (%d declared dead on\n' \
  "$citations_checked" "${#CITATIONS_DEAD_ON_PURPOSE[@]}"
printf '   purpose). %d markdown file(s) checked.\n' "${#files[@]}"
