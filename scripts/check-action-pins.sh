#!/usr/bin/env bash
# Verifies that every third-party GitHub Action used by this repository is referenced by
# an immutable, full-length commit SHA.
#
# WHY
# ---
# `@v3` and `@stable` are MUTABLE pointers. Whoever controls the upstream repository can
# make them resolve to different code whenever they like, and that code then runs inside
# our jobs with whatever those jobs hold — including `desktop-release` and the
# `desktop-bundle` matrix, which decrypt the Desktop updater signing key, and
# `build-images`, which carries `packages: write`. A 40-hex-digit commit SHA is
# content-addressed: it cannot be repointed, so upgrading becomes an explicit, reviewable
# commit instead of something that happens to us between two runs.
#
# POLICY
# ------
#   actions/*, github/*  first-party, published by GitHub itself and already inside the
#                        runner's trust boundary. Allowed on a version tag; pinning them
#                        buys little and costs a bump on every patch release.
#   ./...                actions living in this repository — already at the commit being
#                        built, so there is nothing to pin.
#   everything else      MUST be `owner/repo@<40 hex chars>`, with the human-readable
#                        version kept as a trailing `# vX.Y.Z` comment so a reviewer can
#                        tell what the SHA is supposed to be without resolving it.
#
# Run by the `infra` job in .github/workflows/ci.yml.
#
# Usage: scripts/check-action-pins.sh [workflow-dir]
set -euo pipefail

workflow_dir="${1:-.github/workflows}"

if [ ! -d "$workflow_dir" ]; then
  echo "::error::workflow directory not found: $workflow_dir"
  exit 1
fi

mapfile -t workflows < <(find "$workflow_dir" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)

if [ "${#workflows[@]}" -eq 0 ]; then
  echo "::error::no workflow files found in $workflow_dir — script moved or path changed?"
  exit 1
fi

violations=0
pinned=0
exempt=0

for file in "${workflows[@]}"; do
  lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))

    # Trim leading blanks, then an optional YAML list dash, then blanks again. This is
    # what tells a real `uses:` key apart from the word appearing inside a comment.
    trimmed="${line#"${line%%[![:space:]]*}"}"
    trimmed="${trimmed#- }"
    trimmed="${trimmed#"${trimmed%%[![:space:]]*}"}"
    case "$trimmed" in
      uses:*) ;;
      *) continue ;;
    esac

    # Value of the key: drop `uses:`, drop any trailing comment, drop quotes, trim.
    # `tr` rather than ${var//} so the two quote characters need no escaping games.
    ref="${trimmed#uses:}"
    ref="${ref%%#*}"
    ref="$(printf '%s' "$ref" | tr -d "\"'")"
    ref="${ref#"${ref%%[![:space:]]*}"}"
    ref="${ref%"${ref##*[![:space:]]}"}"

    [ -n "$ref" ] || continue

    case "$ref" in
      ./*)
        exempt=$((exempt + 1))
        continue
        ;;
      actions/*|github/*)
        exempt=$((exempt + 1))
        continue
        ;;
      *@*) ;;
      *)
        echo "::error file=$file,line=$lineno::'$ref' carries no ref at all — pin it to a full 40-character commit SHA."
        violations=$((violations + 1))
        continue
        ;;
    esac

    sha="${ref##*@}"
    if [[ $sha =~ ^[0-9a-f]{40}$ ]]; then
      pinned=$((pinned + 1))
      # The SHA is the control; the comment is what makes it reviewable. Missing one is
      # worth saying out loud, but it is not a security failure, so it does not block.
      case "$line" in
        *'#'*) ;;
        *) echo "::warning file=$file,line=$lineno::'$ref' is pinned but has no trailing version comment (e.g. '# v1.2.3')." ;;
      esac
    else
      echo "::error file=$file,line=$lineno::third-party action '$ref' is on a mutable ref ('$sha'). Pin it to a full 40-character commit SHA and keep the version as a trailing comment."
      violations=$((violations + 1))
    fi
  done < "$file"
done

if [ "$violations" -gt 0 ]; then
  echo "::error::$violations third-party action reference(s) not pinned to a commit SHA."
  exit 1
fi

printf 'Action pins OK — %d third-party reference(s) on commit SHAs, %d first-party/local exempt, across %d workflow file(s).\n' \
  "$pinned" "$exempt" "${#workflows[@]}"
