#!/usr/bin/env bash
# Verifies that every RELATIVE markdown link in the repository resolves to a file
# that exists.
#
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
# Exits non-zero and lists every `file:line` whose target does not exist.
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

files=()
while IFS= read -r f; do
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

if [ "$failures" -gt 0 ]; then
  printf '\n%d broken relative link(s) across %d markdown file(s).\n' "$failures" "${#files[@]}" >&2
  exit 1
fi

printf 'OK — every relative markdown link resolves (%d file(s) checked).\n' "${#files[@]}"
