#!/usr/bin/env bash
# Fails when `docs/api/openapi.yaml` and the Spring controllers disagree about the HTTP
# surface of services/api.
#
# WHY THIS EXISTS
# ---------------
# The spec is written and maintained BY HAND (springdoc is on the classpath but its dump is
# not the deliverable — the hand-written descriptions are). A hand-written contract drifts
# silently: nothing in the build ever compared it to the code, and by 2026-08 it declared 31
# paths against a controller surface of 65, missing `GET /meetings/search` — the product's
# headline AI capability. A contract that describes half the API is worse than none, because
# whoever reads it believes it.
#
# BOTH DIRECTIONS, ON PURPOSE
# ---------------------------
# The check is symmetric, and the two halves fail for different reasons:
#
#   (1) code -> spec  "UNDOCUMENTED": a route exists and the spec never mentions it. This is
#       the drift that accumulates: a new controller ships, nobody edits the YAML.
#
#   (2) spec -> code  "PHANTOM": the spec declares an operation no controller serves. This is
#       the drift a DELETION leaves behind. `POST /speech/token` sat in this file for exactly
#       that reason until the Azure Speech path was removed. A one-way check would have
#       reported green the whole time.
#
# GRANULARITY IS METHOD+PATH, NOT PATH
# ------------------------------------
# Comparing bare paths would pass a `PATCH /tasks/{id}` added under an already-documented
# `/tasks/{id}` key. The unit compared is `METHOD /path`.
#
# ALLOWLIST
# ---------
# Direction (1) accepts a declared allowlist, with the reason written next to each entry, the
# same pattern `check-language.sh` and `check-doc-links.sh` use. A route can be deliberately
# absent from a client-facing contract.
#
# Direction (2) has NO allowlist and will not get one. An operation in the spec that no
# controller serves is not a policy choice, it is a false statement about the product; the
# only fix is to delete it from the spec or to write the handler.
#
# Exits non-zero listing `file:line` for every disagreement.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

SPEC="docs/api/openapi.yaml"
CONTROLLERS="services/api/src/main/java/br/com/nora/api/api/controllers"

# ---------------------------------------------------------------------------
# Routes that exist in the code and are deliberately NOT in the public contract.
# One `METHOD /path` per entry, exactly as the script prints it, with the reason.
#
# Empty today: every route the 16 controllers expose is documented, including the
# operator/control-plane families, which are tagged rather than hidden — see the
# `Platform Admin` and `Platform Internal` tags in the spec.
#
# Format, when one is needed:
#   "GET /internal/platform/debug"   # reason it must not appear in a client contract
# ---------------------------------------------------------------------------
UNDOCUMENTED_ON_PURPOSE=()

is_undocumented_on_purpose() {
  local route="$1" entry
  if [ "${#UNDOCUMENTED_ON_PURPOSE[@]}" -eq 0 ]; then
    return 1
  fi
  for entry in "${UNDOCUMENTED_ON_PURPOSE[@]}"; do
    [ "$entry" = "$route" ] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Side A — the controllers.
#
# Composes the class-level @RequestMapping prefix with each method-level mapping
# annotation. Emits one `METHOD<TAB>/path<TAB>file:line` record per operation.
#
# A file carrying more than one @RequestMapping is a shape this parser does not
# model (method-level @RequestMapping, or a second class in the same file); it is
# reported as an error instead of being silently mis-composed.
# ---------------------------------------------------------------------------
extract_routes_from_controllers() {
  local file
  for file in "$CONTROLLERS"/*.java; do
    awk -v file="$file" '
      function fail(msg) {
        printf "!ERROR\t%s\t%s\n", file, msg
        broken = 1
        exit
      }
      # Pulls the mapping path out of the annotation argument list:
      #   @GetMapping                                 -> ""
      #   @GetMapping("/search")                      -> "/search"
      #   @PostMapping(consumes = MULTIPART...)       -> ""
      #   @PostMapping(value = "/x", consumes = ...)  -> "/x"
      #   @GetMapping(path = "/y", produces = ...)    -> "/y"
      function mapping_path(line,   args, m) {
        if (line !~ /\(/) { return "" }
        args = line
        sub(/^[^(]*\(/, "", args)
        if (match(args, /(value|path)[ \t]*=[ \t]*"[^"]*"/)) {
          m = substr(args, RSTART, RLENGTH)
          sub(/^(value|path)[ \t]*=[ \t]*"/, "", m)
          sub(/"$/, "", m)
          return m
        }
        if (match(args, /^[ \t]*"[^"]*"/)) {
          m = substr(args, RSTART, RLENGTH)
          sub(/^[ \t]*"/, "", m)
          sub(/"$/, "", m)
          return m
        }
        return ""
      }
      function normalise(p,   out) {
        out = p
        gsub(/\/\/+/, "/", out)
        # @PathVariable regex form {id:[0-9]+} -> {id}
        while (match(out, /\{[A-Za-z0-9_]+:[^}]*\}/)) {
          sub(/:[^}]*\}/, "}", out)
        }
        if (length(out) > 1) { sub(/\/$/, "", out) }
        if (out == "") { out = "/" }
        return out
      }
      /@RequestMapping/ {
        seen_request_mapping++
        if (seen_request_mapping > 1) {
          fail("more than one @RequestMapping — this parser only models a single class-level prefix")
        }
        base = mapping_path($0)
        sub(/\/$/, "", base)
        next
      }
      /@(Get|Post|Put|Patch|Delete)Mapping/ {
        verb = $0
        sub(/^.*@/, "", verb)
        sub(/Mapping.*$/, "", verb)
        full = base mapping_path($0)
        printf "%s\t%s\t%s:%d\n", toupper(verb), normalise(full), file, FNR
      }
      END {
        if (broken) { exit 1 }
      }
    ' "$file"
  done
}

# ---------------------------------------------------------------------------
# Side B — the spec.
#
# Reads the `paths:` block only. Path keys are the 2-space-indented lines starting
# with `/`; operations are the 4-space-indented HTTP verbs under them. Anything at
# column 0 ends the block, so `components:` is never walked.
# ---------------------------------------------------------------------------
extract_routes_from_spec() {
  awk -v file="$SPEC" '
    /^paths:[ \t]*$/ { in_paths = 1; next }
    in_paths && /^[^ \t#]/ { in_paths = 0 }
    !in_paths { next }
    /^  \// {
      path = $0
      sub(/^  /, "", path)
      sub(/:[ \t]*$/, "", path)
      next
    }
    /^    (get|put|post|delete|patch|head|options|trace):[ \t]*$/ {
      verb = $1
      sub(/:$/, "", verb)
      printf "%s\t%s\t%s:%d\n", toupper(verb), path, file, FNR
    }
  ' "$SPEC"
}

# ---------------------------------------------------------------------------

if [ ! -d "$CONTROLLERS" ]; then
  printf '%s: controller directory not found — has the package moved?\n' "$CONTROLLERS" >&2
  exit 1
fi
if [ ! -f "$SPEC" ]; then
  printf '%s: spec not found.\n' "$SPEC" >&2
  exit 1
fi

# `|| true` so the awk `exit 1` of the unmodelled-shape guard reaches the report below
# instead of tripping `set -e` on the assignment itself. Every other way of failing to
# parse a controller yields fewer routes, which then surfaces as PHANTOM operations — loud
# either way, never silently green.
code_routes=$(extract_routes_from_controllers || true)

if printf '%s\n' "$code_routes" | grep -q '^!ERROR'; then
  printf '%s\n' "$code_routes" | grep '^!ERROR' | while IFS=$'\t' read -r _ file msg; do
    printf '%s: %s\n' "$file" "$msg" >&2
  done
  exit 1
fi

if [ -z "$code_routes" ]; then
  printf '%s: no mapping annotation found in any controller — the parser and the code disagree.\n' \
    "$CONTROLLERS" >&2
  exit 1
fi

spec_routes=$(extract_routes_from_spec)

if [ -z "$spec_routes" ]; then
  printf '%s: no operation parsed out of the `paths:` block — the parser and the file disagree.\n' \
    "$SPEC" >&2
  exit 1
fi

# `METHOD /path` keys only, for the set comparison.
code_keys=$(printf '%s\n' "$code_routes" | cut -f1,2 | tr '\t' ' ' | sort -u)
spec_keys=$(printf '%s\n' "$spec_routes" | cut -f1,2 | tr '\t' ' ' | sort -u)

undocumented=0
documented_on_purpose=0
while IFS= read -r route; do
  [ -n "$route" ] || continue
  if printf '%s\n' "$spec_keys" | grep -qxF "$route"; then
    continue
  fi
  if is_undocumented_on_purpose "$route"; then
    documented_on_purpose=$((documented_on_purpose + 1))
    continue
  fi
  where=$(printf '%s\n' "$code_routes" | awk -F'\t' -v r="$route" '
    (toupper($1) " " $2) == r { print $3; exit }')
  printf '%s: %s is served here and is MISSING from %s\n' "$where" "$route" "$SPEC"
  undocumented=$((undocumented + 1))
done <<< "$code_keys"

phantom=0
while IFS= read -r route; do
  [ -n "$route" ] || continue
  if printf '%s\n' "$code_keys" | grep -qxF "$route"; then
    continue
  fi
  where=$(printf '%s\n' "$spec_routes" | awk -F'\t' -v r="$route" '
    (toupper($1) " " $2) == r { print $3; exit }')
  printf '%s: %s is declared here and NO controller serves it\n' "$where" "$route"
  phantom=$((phantom + 1))
done <<< "$spec_keys"

total=$((undocumented + phantom))
if [ "$total" -gt 0 ]; then
  printf '\n%d undocumented route(s) and %d phantom operation(s).\n' "$undocumented" "$phantom" >&2
  printf 'Fix the spec (or, for a route that must not be in a client contract, add it to\n' >&2
  printf 'UNDOCUMENTED_ON_PURPOSE at the top of this script WITH the reason).\n' >&2
  exit 1
fi

n_code=$(printf '%s\n' "$code_keys" | grep -c . || true)
if [ "$documented_on_purpose" -gt 0 ]; then
  printf 'OK — %s covers %d of the %d controller operations; %d declared undocumented on purpose.\n' \
    "$SPEC" "$((n_code - documented_on_purpose))" "$n_code" "$documented_on_purpose"
else
  printf 'OK — %s and the controllers agree on all %d operations, in both directions.\n' \
    "$SPEC" "$n_code"
fi
