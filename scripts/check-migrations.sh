#!/usr/bin/env bash
# Mechanical safety net over Flyway migrations. Three rules, each from a failure this
# repository has already had or has written down as its worst case.
#
# WHY THIS EXISTS. `docs/operations/production-readiness-gaps.md` Gap 2 asks for a
# "migrations safety strategy" and offers three options, all of them shaped by the Azure
# deployment that no longer exists (Container Apps revisions, `deploy-infra.yml`
# pre-flight). The substrate is now one bare-metal host running Docker Compose, and Flyway
# runs at Spring startup as the schema owner. The deploy-time half of that gap belongs to
# whoever operates the host. THIS is the half that can be mechanised and put in CI, which
# is the half that catches the mistake before it ever reaches a database.
#
#   1. DUPLICATE VERSION. Two files claiming the same V number is a Flyway boot failure,
#      not a merge conflict — the API refuses to start and nothing in a diff review makes
#      it obvious. It has been a live risk here every time work ran in parallel branches:
#      one wave had three agents each claim ADR 0042, and migration numbers are the same
#      shape of shared resource.
#
#   2. EDITING AN APPLIED MIGRATION. Flyway checksums the migration BODY, comments
#      included. Editing a file that has already run makes every database that ran the old
#      version fail `validate` on startup and refuse to boot until someone runs `flyway
#      repair` there. This is not hypothetical: V027 carries a `!! CHECKSUM WARNING !!`
#      block precisely because it was edited after being applied.
#
#      Checked against the merge base, so it only fires on a file the branch actually
#      changed. An emergency escape hatch exists and is deliberately loud — see
#      ALLOW_MIGRATION_EDIT below.
#
#   3. DESTRUCTIVE DDL WITHOUT ACKNOWLEDGEMENT. Gap 2 states the worst case in its own
#      words: "a migration applies a destructive ALTER TABLE (drop column), then fails,
#      data lost". This does not forbid destructive statements — V027 legitimately DELETEs
#      rows that cross a tenant boundary, which is the entire point of that migration. It
#      requires that the file SAY SO, so that destruction is a decision somebody wrote
#      down rather than a line nobody noticed in review.
#
# Exits non-zero with the file and the reason. Usage: check-migrations.sh [base-ref]
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

MIGRATIONS="services/api/src/main/resources/db/migration"
PLATFORM="services/api/src/main/resources/db/platform"
BASE_REF="${1:-${MIGRATION_BASE_REF:-origin/main}}"

failures=0
report() {
  echo "  $1"
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# 1. Duplicate version numbers, and the naming Flyway needs to parse at all.
# ---------------------------------------------------------------------------
echo "1. version numbers"
for dir in "$MIGRATIONS" "$PLATFORM"; do
  [ -d "$dir" ] || continue
  # `sort | uniq -d` over the parsed version, per directory: the two directories are
  # separate Flyway histories (ADR 0022 gives the control plane its own database), so a
  # V001 in each is correct and must not be reported.
  dupes=$(
    for f in "$dir"/V*.sql; do
      [ -e "$f" ] || continue
      basename "$f" | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p'
    done | sort | uniq -d
  )
  for v in $dupes; do
    report "duplicate version V$v in $dir: $(ls "$dir"/V"$v"__*.sql | tr '\n' ' ')"
  done

  for f in "$dir"/*.sql; do
    [ -e "$f" ] || continue
    b=$(basename "$f")
    # Flyway's own naming rule. A file that does not match is silently NOT a migration,
    # which is the quietest possible way for a schema change to never run.
    if ! printf '%s' "$b" | grep -qE '^([VR])[0-9]*__[a-z0-9_]+\.sql$'; then
      report "name is not a Flyway migration: $dir/$b (expected V<n>__snake_case.sql)"
    fi
  done
done

# ---------------------------------------------------------------------------
# 2. An already-applied migration must not be edited.
# ---------------------------------------------------------------------------
# "Already applied" is approximated by "exists on the base branch", which is the best a CI
# job can know without reading a production database — and it is the right approximation,
# because a migration that reached `main` is one a deployment has run or is about to.
echo "2. edits to migrations that already exist on $BASE_REF"
if git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  changed=$(git diff --name-only --diff-filter=M "$BASE_REF"...HEAD -- "$MIGRATIONS" "$PLATFORM" || true)
  for f in $changed; do
    # The escape hatch is a marker inside the file, not a CI flag: it survives in the tree
    # where the next reader sees it, and it forces the person doing it to write down why.
    # V027 is exactly this case, and its warning block is the precedent for the wording.
    if grep -q 'ALLOW_MIGRATION_EDIT' "$f" 2>/dev/null; then
      echo "  ACKNOWLEDGED edit: $f (carries ALLOW_MIGRATION_EDIT)"
      continue
    fi
    report "edited after reaching $BASE_REF: $f"
    report "    Flyway checksums the body, comments included. Every database that ran the"
    report "    old version will fail validate on startup and refuse to boot until someone"
    report "    runs 'flyway repair' there. Write a NEW migration instead. If the edit is"
    report "    genuinely unavoidable, add an ALLOW_MIGRATION_EDIT comment saying why —"
    report "    see the !! CHECKSUM WARNING !! block in V027 for the shape."
  done
else
  echo "  skipped: $BASE_REF is not available in this checkout"
fi

# ---------------------------------------------------------------------------
# 3. Destructive DDL has to be acknowledged in the file.
# ---------------------------------------------------------------------------
echo "3. destructive statements (in migrations ADDED by this branch)"
# SCOPED TO NEW FILES, and that scoping is not a softening — it is the only coherent rule.
# Running this over the whole tree on first write reported three already-applied migrations
# (V018, V027 and platform V002), and the "fix" it demanded was to edit them and add the
# acknowledgement comment. That is precisely what rule 2 forbids: Flyway checksums the body,
# comments included, so retrofitting the marker would break every database that had run
# them. A guard whose remedy trips its own sibling rule is wrong, not strict.
#
# Acknowledgement therefore belongs to the moment of authorship, which is exactly when a
# reviewer can still ask "should this really delete that?".
# Deliberately narrow. Each of these can lose data that no later migration can recreate.
# NOT listed, on purpose: DROP INDEX, DROP CONSTRAINT, DROP POLICY and DROP DEFAULT —
# every one is recreatable from the schema, so flagging them would train people to add the
# acknowledgement reflexively, which is how a guard stops meaning anything.
DESTRUCTIVE='DROP[[:space:]]+TABLE|DROP[[:space:]]+COLUMN|DROP[[:space:]]+SCHEMA|TRUNCATE|DELETE[[:space:]]+FROM|ALTER[[:space:]]+COLUMN[[:space:]]+[a-z_]+[[:space:]]+TYPE'
if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  echo "  skipped: $BASE_REF is not available, so 'added by this branch' cannot be determined"
  NEW_MIGRATIONS=""
else
  NEW_MIGRATIONS=$(git diff --name-only --diff-filter=A "$BASE_REF"...HEAD -- "$MIGRATIONS" "$PLATFORM" || true)
  [ -n "$NEW_MIGRATIONS" ] || echo "  none added by this branch"
fi
for f in $NEW_MIGRATIONS; do
  case "$f" in
    *.sql) ;;
    *) continue ;;
  esac
  [ -f "$f" ] || continue
  # Strip comment lines before matching: a migration that merely DESCRIBES a DELETE in its
  # header is not performing one, and this file's own prose would otherwise trip it.
  body=$(grep -vE '^[[:space:]]*--' "$f" || true)
  hit=$(printf '%s' "$body" | grep -inE "$DESTRUCTIVE" | head -1 || true)
  [ -n "$hit" ] || continue
  if grep -qiE 'DESTRUCTIVE:' "$f"; then
    echo "  ACKNOWLEDGED: $f"
    continue
  fi
  report "destructive statement without acknowledgement: $f"
  report "    matched: $(printf '%s' "$hit" | cut -c1-100)"
  report "    Add a comment line starting with 'DESTRUCTIVE:' saying what is removed and"
  report "    why it cannot be recovered. This does not forbid the statement — V027"
  report "    legitimately deletes rows that cross a tenant boundary — it requires that"
  report "    the destruction be something somebody wrote down."
done

echo ""
if [ "$failures" -gt 0 ]; then
  echo "$failures migration safety finding(s)."
  exit 1
fi
echo "OK: migration versions unique, no applied migration edited, destructive DDL acknowledged."
