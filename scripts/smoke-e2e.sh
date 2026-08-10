#!/usr/bin/env bash
#
# smoke-e2e.sh — end-to-end proof that a NORA deployment actually works.
#
# This is what "it works" means. Everything here goes over the public HTTPS API, exactly
# as a browser or the desktop client would; nothing reaches into the host or the database
# except the one step that is genuinely out of band (see EMAIL CONFIRMATION below).
#
# WHAT IT PROVES, in order:
#   1. signup            a new tenant and its root user are created
#   2. login is REFUSED  before the address is confirmed (the gate exists and works)
#   3. confirm + login   the session is issued and carries the tenant
#   4. upload            a transcript is accepted and queued for analysis
#   5. analysis          a summary comes back, with decisions and action items
#   6. isolation         a SECOND tenant gets 404 on the first tenant's meeting
#
# Step 6 is the one worth having. It is the only assertion in this repository that exercises
# tenant isolation against the deployed stack rather than against a test database, and it is
# written to fail loudly: a 200 or a 403 both fail, because 403 would mean authorization
# refused it and isolation was never reached.
#
# EMAIL CONFIRMATION
#   Confirming an address requires a token that only exists in the e-mail. Three ways in,
#   tried in this order:
#     1. The signup response carries emailVerificationDevToken — true only when the API runs
#        with EXPOSE_DEV_TOKENS=true (local, CI). Nothing to configure.
#     2. $NORA_SMOKE_CONFIRM_CMD is set — an operator-supplied command, called with the
#        e-mail address as $1, that confirms it however that deployment can. On the
#        production host that is a psql UPDATE; see docs/operations/host-deploy.md.
#     3. Neither — the script stops after step 2 with a clear message, having still proved
#        that signup works and that the verification gate refuses an unconfirmed login.
#        It exits non-zero, because a partial run is not a pass.
#
# USAGE
#   API_BASE=http://localhost:8080     scripts/smoke-e2e.sh
#   API_BASE=https://api.nora.systems  scripts/smoke-e2e.sh
#
#   API_BASE has NO DEFAULT, on purpose. This script creates two tenants and two root users
#   that no endpoint can delete, and runs a real analysis against whatever provider key the
#   target holds. A default would make "run it and see" quietly mean "add two permanent
#   accounts to production", which is not something to get by forgetting a variable.
#
# ENVIRONMENT
#   API_BASE                 REQUIRED. Base URL of the API, e.g. https://api.nora.systems
#   NORA_SMOKE_CONFIRM_CMD   see EMAIL CONFIRMATION above
#   NORA_SMOKE_EMAIL_DOMAIN  default smoke.invalid — RFC 2606 reserved, so a stray send bounces
#                            instead of reaching a real person
#   NORA_SMOKE_TRANSCRIPT    transcript to upload, default data/samples/smoke-e2e-transcript.txt
#   NORA_SMOKE_TIMEOUT       seconds to wait for the analysis, default 180
#   NORA_SMOKE_KEEP          set to 1 to skip the cleanup and inspect what was created
#
# EXIT CODES
#   0 every step passed · 1 a step failed · 2 a prerequisite is missing
#
set -uo pipefail

if [ -z "${API_BASE:-}" ]; then
  cat >&2 <<'USAGE'
API_BASE is required — this script has no default target.

  API_BASE=http://localhost:8080     scripts/smoke-e2e.sh
  API_BASE=https://api.nora.systems  scripts/smoke-e2e.sh

It creates two tenants and two root users that no endpoint can delete, and runs a real
analysis against the target's provider key. Naming the target is the point.
USAGE
  exit 2
fi
API_BASE="${API_BASE%/}"
EMAIL_DOMAIN="${NORA_SMOKE_EMAIL_DOMAIN:-smoke.invalid}"
TIMEOUT="${NORA_SMOKE_TIMEOUT:-180}"
KEEP="${NORA_SMOKE_KEEP:-0}"

# Colour only when attached to a terminal, so CI logs stay readable.
if [ -t 1 ]; then
  B=$'\033[1m'; G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; Z=$'\033[0m'
else
  B=''; G=''; R=''; Y=''; Z=''
fi

STEP=0
FAILED=0
step()  { STEP=$((STEP + 1)); printf '\n%s[%d] %s%s\n' "$B" "$STEP" "$*" "$Z"; }
ok()    { printf '    %sok%s   %s\n' "$G" "$Z" "$*"; }
warn()  { printf '    %swarn%s %s\n' "$Y" "$Z" "$*"; }
fail()  { printf '    %sFAIL%s %s\n' "$R" "$Z" "$*"; FAILED=1; }
die()   { printf '\n%sFAILED%s %s\n' "$R" "$Z" "$*"; exit 1; }
need()  { command -v "$1" >/dev/null 2>&1 || { printf '%s not found — required.\n' "$1" >&2; exit 2; }; }

need curl
need jq

WORK="$(mktemp -d)" || { echo "mktemp -d failed" >&2; exit 2; }
[ -n "$WORK" ] && [ -d "$WORK" ] || { echo "mktemp -d produced no directory" >&2; exit 2; }
cleanup_tmp() { rm -rf "$WORK"; }
trap cleanup_tmp EXIT

# The transcript is a repository fixture rather than a heredoc: it is pt-BR, and pt-BR fixture
# data lives under data/ (which is where the language check expects to find it). It also means
# the smoke exercises the same input shape the rest of the test suite does.
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TRANSCRIPT="${NORA_SMOKE_TRANSCRIPT:-$REPO_ROOT/data/samples/smoke-e2e-transcript.txt}"
[ -f "$TRANSCRIPT" ] || { echo "transcript not found: $TRANSCRIPT" >&2; exit 2; }

# A run id keeps two concurrent runs from colliding on an address.
RUN="$(date -u +%Y%m%d%H%M%S)-$$"
EMAIL_A="smoke-a-${RUN}@${EMAIL_DOMAIN}"
EMAIL_B="smoke-b-${RUN}@${EMAIL_DOMAIN}"
# Long, mixed, and generated per run: the password policy rejects weak ones, and a fixed
# password in a public repository is a credential whether it guards anything or not.
PASSWORD="Smoke!$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)aA1"

# api <method> <path> <token|-> [curl args...]
# Writes the body to $WORK/body and returns the status code on stdout.
api() {
  local method=$1 path=$2 token=$3; shift 3
  local -a args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$method" "${API_BASE}${path}"
                 --max-time 60 -H 'Accept: application/json')
  [ "$token" != "-" ] && args+=(-H "Authorization: Bearer $token")
  curl "${args[@]}" "$@" 2>"$WORK/curlerr" || { cat "$WORK/curlerr" >&2; echo 000; return 0; }
}

body()      { cat "$WORK/body"; }
body_json() { jq -r "$1" <"$WORK/body" 2>/dev/null; }

printf '%sNORA end-to-end smoke%s\n' "$B" "$Z"
printf '    target   %s\n' "$API_BASE"
printf '    run      %s\n' "$RUN"

# ---------------------------------------------------------------------------
step "health"
# ---------------------------------------------------------------------------
code=$(api GET /actuator/health -)
[ "$code" = "200" ] || die "health returned $code — the deployment is not up, so nothing below would mean anything."
ok "actuator/health 200, status=$(body_json '.status')"

# ---------------------------------------------------------------------------
step "signup — tenant A"
# ---------------------------------------------------------------------------
signup() {
  api POST /auth/signup - -H 'Content-Type: application/json' --data @- <<JSON
{"email":"$1","password":"$PASSWORD","displayName":"Smoke $2","companyName":"Smoke Co $2 $RUN","role":"OTHER"}
JSON
}

code=$(signup "$EMAIL_A" A)
[ "$code" = "201" ] || die "signup returned $code, expected 201. Body: $(body)"
TENANT_A=$(body_json '.tenantId')
DEV_TOKEN_A=$(body_json '.emailVerificationDevToken // empty')
ok "created tenant $TENANT_A"

# ---------------------------------------------------------------------------
step "login is refused before the address is confirmed"
# ---------------------------------------------------------------------------
login() {
  api POST /auth/login - -H 'Content-Type: application/json' \
      -H 'X-NORA-Client: native' --data @- <<JSON
{"email":"$1","password":"$PASSWORD"}
JSON
}

code=$(login "$EMAIL_A")
errcode=$(body_json '.code // empty')
if [ "$code" = "200" ]; then
  fail "login succeeded on an unconfirmed address — the verification gate is not enforced"
elif [ "$errcode" = "EMAIL_NOT_VERIFIED" ]; then
  ok "refused with EMAIL_NOT_VERIFIED"
else
  warn "refused with $code / ${errcode:-no code} — expected EMAIL_NOT_VERIFIED"
fi

# ---------------------------------------------------------------------------
step "confirm the address"
# ---------------------------------------------------------------------------
confirm() {
  local email=$1 dev_token=$2
  if [ -n "$dev_token" ]; then
    local c; c=$(api POST /auth/verify-email - -H 'Content-Type: application/json' \
                     --data "{\"token\":\"$dev_token\"}")
    [ "$c" = "204" ] || { fail "verify-email returned $c"; return 1; }
    ok "confirmed $email with the dev token"
    return 0
  fi
  if [ -n "${NORA_SMOKE_CONFIRM_CMD:-}" ]; then
    # shellcheck disable=SC2086
    if $NORA_SMOKE_CONFIRM_CMD "$email" >"$WORK/confirm.log" 2>&1; then
      ok "confirmed $email via NORA_SMOKE_CONFIRM_CMD"
      return 0
    fi
    fail "NORA_SMOKE_CONFIRM_CMD failed: $(tail -3 "$WORK/confirm.log")"
    return 1
  fi
  return 2
}

confirm "$EMAIL_A" "$DEV_TOKEN_A"; rc=$?
if [ "$rc" = "2" ]; then
  printf '\n%sSTOPPED%s after step %d.\n' "$Y" "$Z" "$STEP"
  printf '  Signup works and the verification gate refuses an unconfirmed login — both proved above.\n'
  printf '  Going further needs the confirmation token, which exists only in the e-mail.\n'
  printf '  Set NORA_SMOKE_CONFIRM_CMD (see the header of this script), or point API_BASE at a\n'
  printf '  deployment running with EXPOSE_DEV_TOKENS=true.\n'
  exit 1
fi
[ "$rc" = "0" ] || die "could not confirm $EMAIL_A"

# ---------------------------------------------------------------------------
step "login — tenant A"
# ---------------------------------------------------------------------------
code=$(login "$EMAIL_A")
[ "$code" = "200" ] || die "login returned $code. Body: $(body)"
TOKEN_A=$(body_json '.accessToken')
[ -n "$TOKEN_A" ] && [ "$TOKEN_A" != "null" ] || die "login 200 but no accessToken in the body — is the X-NORA-Client header being honoured?"
[ "$(body_json '.tenantId')" = "$TENANT_A" ] || fail "login returned a different tenantId than signup"
ok "session issued for tenant $TENANT_A"

# ---------------------------------------------------------------------------
step "upload a transcript"
# ---------------------------------------------------------------------------
# The fixture contains a decision and an action item with an owner, so the assertions below
# can be about content rather than about the shape of the response.
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cat >"$WORK/metadata.json" <<JSON
{"title":"Smoke $RUN",
 "startedAt":"$now","endedAt":"$now","language":"pt-BR","transcriptFormat":"txt",
 "participants":[{"displayName":"Smoke A","isInternal":true},{"displayName":"Smoke B","isInternal":false}],
 "tags":["smoke"]}
JSON

code=$(api POST /meetings "$TOKEN_A" \
        -F "metadata=<$WORK/metadata.json;type=application/json" \
        -F "file=@$TRANSCRIPT;type=text/plain")
[ "$code" = "201" ] || [ "$code" = "202" ] || [ "$code" = "200" ] || die "upload returned $code. Body: $(body)"
MEETING_ID=$(body_json '.id')
[ -n "$MEETING_ID" ] && [ "$MEETING_ID" != "null" ] || die "upload $code but no meeting id. Body: $(body)"
ok "meeting $MEETING_ID accepted"

# ---------------------------------------------------------------------------
step "wait for the analysis"
# ---------------------------------------------------------------------------
deadline=$(( $(date +%s) + TIMEOUT ))
status=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  code=$(api GET "/meetings/$MEETING_ID" "$TOKEN_A")
  [ "$code" = "200" ] || die "meeting detail returned $code. Body: $(body)"
  status=$(body_json '.processingStatus')
  case "$status" in
    COMPLETED|ANALYZED|DONE) break ;;
    FAILED|ERROR)            die "analysis ended in $status" ;;
  esac
  # A summary present with any status is good enough — the status vocabulary has changed
  # before, and what this step is really asserting is that analysis output exists.
  [ "$(body_json '.analysis.summary // empty')" != "" ] && break
  sleep 5
done
[ "$(body_json '.analysis // empty')" != "" ] || die "no analysis after ${TIMEOUT}s (last status: ${status:-unknown}). Is the worker up, and does it have a provider key or USE_LLM_STUB?"
ok "processingStatus=$status"

summary=$(body_json '.analysis.summary // empty')
decisions=$(body_json '.analysis.decisions | length // 0')
actions=$(body_json '.analysis.actionItems | length // 0')

[ -n "$summary" ]     && ok "summary: $(printf '%.90s' "$summary")..." || fail "analysis has no summary"
[ "$decisions" -gt 0 ] && ok "$decisions decision(s)"                   || fail "analysis found no decisions in a transcript that contains one"
[ "$actions"   -gt 0 ] && ok "$actions action item(s)"                  || fail "analysis found no action items in a transcript that contains one"

# ---------------------------------------------------------------------------
step "a second tenant cannot read the first one's meeting"
# ---------------------------------------------------------------------------
code=$(signup "$EMAIL_B" B)
[ "$code" = "201" ] || die "signup B returned $code. Body: $(body)"
TENANT_B=$(body_json '.tenantId')
DEV_TOKEN_B=$(body_json '.emailVerificationDevToken // empty')
[ "$TENANT_B" != "$TENANT_A" ] || die "signup B landed in tenant A — that is the isolation failure itself"

confirm "$EMAIL_B" "$DEV_TOKEN_B" || die "could not confirm $EMAIL_B"
code=$(login "$EMAIL_B")
[ "$code" = "200" ] || die "login B returned $code"
TOKEN_B=$(body_json '.accessToken')
# Without this the next request would go out unauthenticated. It would still be caught — an
# anonymous GET is 401 and the case below fails on anything that is not 404 — but "isolation
# holds" and "we forgot the token" must not be able to produce the same line of output.
[ -n "$TOKEN_B" ] && [ "$TOKEN_B" != "null" ] || die "login B 200 but no accessToken in the body"

# The intruder is tenant B's ROOT — the strongest principal that exists. Authorization
# cannot be what refuses it, so the tenant filter is the only thing that can. A 403 here
# would mean this assertion never reached isolation and is not evidence of anything.
code=$(api GET "/meetings/$MEETING_ID" "$TOKEN_B")
case "$code" in
  404) ok "tenant B root gets 404 on tenant A's meeting" ;;
  200) fail "TENANT LEAK: tenant B root read tenant A's meeting" ;;
  403) fail "403, not 404 — authorization refused before tenant scoping, so this run proves nothing about isolation" ;;
  *)   fail "unexpected $code" ;;
esac

# The listing must not leak it either: a detail endpoint can be scoped while a list is not.
code=$(api GET "/meetings" "$TOKEN_B")
if [ "$code" = "200" ]; then
  if [ "$(jq -r --arg id "$MEETING_ID" '[.. | objects | select(.id? == $id)] | length' <"$WORK/body")" = "0" ]; then
    ok "tenant B's listing does not contain it"
  else
    fail "TENANT LEAK: tenant A's meeting appears in tenant B's listing"
  fi
else
  warn "listing for tenant B returned $code — not checked"
fi

# ---------------------------------------------------------------------------
step "cleanup"
# ---------------------------------------------------------------------------
if [ "$KEEP" = "1" ]; then
  warn "NORA_SMOKE_KEEP=1 — leaving meeting $MEETING_ID and both tenants in place"
else
  code=$(api DELETE "/privacy/meetings/$MEETING_ID" "$TOKEN_A")
  case "$code" in
    200|204) ok "meeting hard-erased" ;;
    *)       warn "erase returned $code — meeting $MEETING_ID stays behind" ;;
  esac
  warn "the two smoke tenants are not deleted: there is no tenant-delete endpoint. They are"
  warn "inert (addresses under $EMAIL_DOMAIN, which cannot receive mail) but they accumulate."
fi

printf '\n'
if [ "$FAILED" = "0" ]; then
  printf '%sPASS%s — %d steps, against %s\n' "$G" "$Z" "$STEP" "$API_BASE"
  exit 0
fi
printf '%sFAIL%s — see the steps marked FAIL above\n' "$R" "$Z"
exit 1
