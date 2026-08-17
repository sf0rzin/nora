#!/usr/bin/env bash
#
# seed-demo.sh — populates a NORA environment with the demonstration narrative.
#
# Everything here goes over the public HTTP API, exactly as the web app would. There is no
# SQL, and that is deliberate: the schema has RLS, soft-delete and composite foreign keys,
# and a raw INSERT would skip every validation the product applies — producing a database
# state no real usage could reach, which is the opposite of what a demonstration wants.
#
# WHAT IT CREATES
#   Tenant 1 — Core (Lucas Almeida, root of his own personal workspace)
#     company context + 2 analysed meetings, so the dashboard and the task list are not empty.
#
#   Tenant 2 — Enterprise (Camila Souza, root)
#     corporate e-mail domain, company context (Meridian, a fictional ERP vendor), IAM group
#     "Comercial Sul", a scoped read policy attached to it, a pending invitation for Rafael
#     Costa carrying that group, and 5 analysed meetings: a 3-meeting arc against ONE customer
#     account (so Customer Confidence has a history and the backend can compute a trend), one
#     meeting in another region, and one internal meeting with no customer at all.
#
# WHAT IT CANNOT DO, AND WHY
#   It cannot finish creating Rafael. Accepting an invitation requires the raw token, and
#   InvitationService persists only its SHA-256 — the raw value exists for the length of one
#   method call, is rendered into the invitation e-mail, and is never returned by any endpoint
#   nor written to any log (LogEmailSender suppresses the link on purpose). So:
#     * without a token, the seed creates the invitation and stops there. Everything Rafael
#       would need is already in place; only his acceptance is missing.
#     * with NORA_SEED_RAFAEL_TOKEN set — taken from the invitation e-mail, which needs
#       RESEND_API_KEY configured on the API — it accepts, logs in, and prints what Rafael can
#       and cannot see, which is the Enterprise scoping demonstration in one screen.
#
# WHAT IT CANNOT PROMISE
#   Customer Confidence needs the analyser to name the customer account. CustomerConfidenceService
#   is a documented no-op when accountName is null or blank, and the deterministic stub
#   (USE_LLM_STUB=true) always emits null — it says so in its own code. So with the stub the
#   meetings are analysed, but no account, assessment or trend is created. The seed checks this
#   and reports it rather than leaving an empty card to be discovered on stage.
#
# USAGE
#   API_BASE=http://localhost:8080 scripts/seed-demo.sh
#
#   API_BASE has NO DEFAULT, for the reason smoke-e2e.sh gives: this creates tenants and root
#   users that no endpoint can delete. Naming the target is the point.
#
# ENVIRONMENT
#   API_BASE                REQUIRED. Base URL of the API, e.g. http://localhost:8080
#   NORA_SEED_DOMAIN        e-mail domain for the seeded users. Default demo.invalid
#                           (RFC 2606 reserved, so a stray send bounces instead of arriving)
#   NORA_SEED_PASSWORD      password for every seeded user. Default: generated per run and
#                           printed at the end. A fixed password committed to a public
#                           repository is a credential whether or not it guards anything
#   NORA_SEED_RAFAEL_TOKEN  invitation token, to finish creating the Enterprise member
#   NORA_SEED_TIMEOUT       seconds to wait for each analysis, default 240
#   NORA_SEED_PREFIX        prefix for the seeded addresses, default demo. Change it to run
#                           the seed twice against the same environment
#
# EXIT CODES
#   0 the seed completed · 1 a step failed · 2 a prerequisite is missing
#
set -uo pipefail

if [ -z "${API_BASE:-}" ]; then
  cat >&2 <<'USAGE'
API_BASE is required — this script has no default target.

  API_BASE=http://localhost:8080 scripts/seed-demo.sh

It creates tenants and root users that no endpoint can delete. Naming the target is the point.
USAGE
  exit 2
fi
API_BASE="${API_BASE%/}"
DOMAIN="${NORA_SEED_DOMAIN:-demo.invalid}"
PREFIX="${NORA_SEED_PREFIX:-demo}"
TIMEOUT="${NORA_SEED_TIMEOUT:-240}"

if [ -t 1 ]; then
  B=$'\033[1m'; G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; Z=$'\033[0m'
else
  B=''; G=''; R=''; Y=''; Z=''
fi

STEP=0
step()  { STEP=$((STEP + 1)); printf '\n%s[%d] %s%s\n' "$B" "$STEP" "$*" "$Z"; }
ok()    { printf '    %sok%s   %s\n' "$G" "$Z" "$*"; }
warn()  { printf '    %swarn%s %s\n' "$Y" "$Z" "$*"; }
die()   { printf '\n%sFAILED%s %s\n' "$R" "$Z" "$*" >&2; exit 1; }
need()  { command -v "$1" >/dev/null 2>&1 || { printf '%s not found — required.\n' "$1" >&2; exit 2; }; }

need curl
need jq

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$REPO_ROOT/data/synthetic"
[ -d "$DATA" ] || die "data/synthetic not found under $REPO_ROOT"

WORK="$(mktemp -d)" || { echo "mktemp -d failed" >&2; exit 2; }
[ -n "$WORK" ] && [ -d "$WORK" ] || { echo "mktemp -d produced no directory" >&2; exit 2; }
trap 'rm -rf "$WORK"' EXIT

GENERATED_PASSWORD=0
if [ -z "${NORA_SEED_PASSWORD:-}" ]; then
  PASSWORD="Demo$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 14)7a"
  GENERATED_PASSWORD=1
else
  PASSWORD="$NORA_SEED_PASSWORD"
fi

EMAIL_LUCAS="${PREFIX}-lucas@${DOMAIN}"
EMAIL_CAMILA="${PREFIX}-camila@${DOMAIN}"
EMAIL_RAFAEL="${PREFIX}-rafael@${DOMAIN}"

# api <method> <path> <token|-> [curl args...] — body goes to $WORK/body, status to stdout.
api() {
  local method=$1 path=$2 token=$3; shift 3
  local -a args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$method" "${API_BASE}${path}"
                 --max-time 120 -H 'Accept: application/json')
  [ "$token" != "-" ] && args+=(-H "Authorization: Bearer $token")
  curl "${args[@]}" "$@" 2>"$WORK/curlerr" || { cat "$WORK/curlerr" >&2; echo 000; return 0; }
}
body()      { cat "$WORK/body"; }
body_json() { jq -r "$1" <"$WORK/body" 2>/dev/null; }

# --- identity ---------------------------------------------------------------

# Prints "<tenantId> <accessToken>" on stdout. Every diagnostic goes to stderr so that the
# caller's command substitution captures only the two values.
signup_verify_login() {
  local email=$1 display=$2 company=$3 code tenant dev token

  code=$(api POST /auth/signup - -H 'Content-Type: application/json' --data @- <<JSON
{"email":"$email","password":"$PASSWORD","displayName":"$display","companyName":"$company","role":"OTHER"}
JSON
)
  [ "$code" = "201" ] || die "signup for $email returned $code. Body: $(body)"
  tenant=$(body_json '.tenantId')
  dev=$(body_json '.emailVerificationDevToken // empty')

  if [ -z "$dev" ]; then
    cat >&2 <<MSG

The signup succeeded but the API did not return emailVerificationDevToken, so this script
cannot confirm the address — and without a confirmed address there is no login, so nothing
below could run.

That field comes back only when the API runs with nora.security.expose-dev-tokens=true, which
is the default of the local profile and of the test profile. Point API_BASE at a local API, or
set EXPOSE_DEV_TOKENS=true on the target, and run again.

This is not the situation scripts/smoke-e2e.sh handles with NORA_SMOKE_CONFIRM_CMD: that test
only needs an address confirmed, while this seed needs a working session for three users.
MSG
    exit 1
  fi

  code=$(api POST /auth/verify-email - -H 'Content-Type: application/json' \
             --data "{\"token\":\"$dev\"}")
  [ "$code" = "204" ] || die "verify-email for $email returned $code. Body: $(body)"

  code=$(api POST /auth/login - -H 'Content-Type: application/json' \
             -H 'X-NORA-Client: native' \
             --data "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")
  [ "$code" = "200" ] || die "login for $email returned $code. Body: $(body)"
  token=$(body_json '.accessToken')
  [ -n "$token" ] && [ "$token" != "null" ] || die "login for $email returned no accessToken"
  printf '%s %s\n' "$tenant" "$token"
}

# --- meetings ---------------------------------------------------------------

# upload_meeting <token> <file> <title> <startedAt> <attributesJson> -> meeting id on stdout
upload_meeting() {
  local token=$1 file=$2 title=$3 started=$4 attrs=$5 code id
  [ -f "$file" ] || die "transcript not found: $file"

  jq -n --arg t "$title" --arg s "$started" --argjson a "$attrs" \
     '{title:$t, startedAt:$s, endedAt:$s, language:"pt-BR", transcriptFormat:"TXT",
       tags:["demo"], attributes:$a}' >"$WORK/metadata.json" \
    || die "could not build the upload metadata for $(basename "$file")"

  code=$(api POST /meetings "$token" \
          -F "metadata=<$WORK/metadata.json;type=application/json" \
          -F "file=@$file;type=text/plain")
  case "$code" in
    200|201|202) ;;
    *) die "upload of $(basename "$file") returned $code. Body: $(body)" ;;
  esac
  id=$(body_json '.id')
  [ -n "$id" ] && [ "$id" != "null" ] || die "upload of $(basename "$file") returned no id"
  printf '%s\n' "$id"
}

# wait_for_analysis <token> <meetingId> -> 0 when an analysis exists, 1 otherwise.
#
# The arc meetings are uploaded one at a time and awaited in narrative order on purpose.
# CustomerConfidenceService.computeTrend compares against the newest PRIOR assessment ordered
# by created_at, so overlapping analyses would chain the trend in whatever order they happened
# to finish rather than in the order the meetings happened.
wait_for_analysis() {
  local token=$1 id=$2 deadline status='' code
  deadline=$(( $(date +%s) + TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code=$(api GET "/meetings/$id" "$token")
    [ "$code" = "200" ] || die "meeting detail for $id returned $code. Body: $(body)"
    [ "$(body_json '.analysis // empty')" != "" ] && return 0
    status=$(body_json '.processingStatus')
    case "$status" in
      FAILED|ERROR) warn "analysis of $id ended in $status"; return 1 ;;
    esac
    sleep 5
  done
  warn "no analysis for $id after ${TIMEOUT}s (last status: ${status:-unknown}). Is the worker up,"
  warn "and does it have USE_LLM_STUB=true or a provider key?"
  return 1
}

printf '%sNORA demonstration seed%s\n' "$B" "$Z"
printf '    target   %s\n' "$API_BASE"
printf '    domain   %s\n' "$DOMAIN"

# ---------------------------------------------------------------------------
step "health"
# ---------------------------------------------------------------------------
code=$(api GET /actuator/health -)
[ "$code" = "200" ] || die "health returned $code — the API is not up, so nothing below would work."
ok "actuator/health 200"

# ---------------------------------------------------------------------------
step "Core workspace — Lucas Almeida"
# ---------------------------------------------------------------------------
CORE_IDENTITY=$(signup_verify_login "$EMAIL_LUCAS" "Lucas Almeida" "Solo Launch") || exit 1
TENANT_LUCAS=${CORE_IDENTITY%% *}
TOKEN_LUCAS=${CORE_IDENTITY##* }
ok "tenant $TENANT_LUCAS, session issued"

code=$(api PUT /tenant/context "$TOKEN_LUCAS" -H 'Content-Type: application/json' \
        --data @"$DATA/tenants/solo-launch.context.json")
[ "$code" = "200" ] || die "PUT /tenant/context for Lucas returned $code. Body: $(body)"
ok "company context loaded from solo-launch.context.json"

M_L1=$(upload_meeting "$TOKEN_LUCAS" "$DATA/meetings/10-solo-standup-bug-agendamento.txt" \
        "Stand-up de engenharia" "2026-08-10T13:00:00Z" '{}') || exit 1
wait_for_analysis "$TOKEN_LUCAS" "$M_L1" && ok "meeting $M_L1 analysed"

M_L2=$(upload_meeting "$TOKEN_LUCAS" "$DATA/meetings/12-solo-sprint-planning.txt" \
        "Sprint planning" "2026-08-11T13:00:00Z" '{}') || exit 1
wait_for_analysis "$TOKEN_LUCAS" "$M_L2" && ok "meeting $M_L2 analysed"

api GET /tasks "$TOKEN_LUCAS" >/dev/null
ok "$(body_json '.items | length') extracted task(s) in Lucas's workspace"

# ---------------------------------------------------------------------------
step "Enterprise workspace — Camila Souza (root)"
# ---------------------------------------------------------------------------
ENT_IDENTITY=$(signup_verify_login "$EMAIL_CAMILA" "Camila Souza" "Meridian Sistemas de Gestao") || exit 1
TENANT_CAMILA=${ENT_IDENTITY%% *}
TOKEN_CAMILA=${ENT_IDENTITY##* }
ok "tenant $TENANT_CAMILA, session issued"

code=$(api PUT /tenant/domain "$TOKEN_CAMILA" -H 'Content-Type: application/json' \
        --data "{\"allowedEmailDomain\":\"$DOMAIN\"}")
[ "$code" = "200" ] || die "PUT /tenant/domain returned $code. Body: $(body)"
ok "corporate e-mail domain set to $DOMAIN"

code=$(api PUT /tenant/context "$TOKEN_CAMILA" -H 'Content-Type: application/json' \
        --data @"$DATA/tenants/meridian-erp.context.json")
[ "$code" = "200" ] || die "PUT /tenant/context for Camila returned $code. Body: $(body)"
ok "company context loaded from meridian-erp.context.json"

# ---------------------------------------------------------------------------
step "IAM — group, scoped policy, attachment, invitation"
# ---------------------------------------------------------------------------
code=$(api POST /iam/groups "$TOKEN_CAMILA" -H 'Content-Type: application/json' --data @- <<'JSON'
{"name":"Comercial Sul","description":"Account executives responsible for the southern region"}
JSON
)
case "$code" in 200|201) ;; *) die "POST /iam/groups returned $code. Body: $(body)" ;; esac
GROUP_ID=$(body_json '.id')
ok "group Comercial Sul = $GROUP_ID"

# The condition key is resolved in the request context, and for a meeting that context IS the
# attributes map sent at upload — MeetingsController hands Meeting::attributes to the evaluator
# on both the listing and the detail. So `region` below is not a reserved word: it is whatever
# the uploader wrote.
#
# Only meeting:read is granted, and that is a deliberate limit rather than an oversight. Tasks
# reach the evaluator with an EMPTY context (TasksController.list passes Map.of()), so no
# condition can scope them by region; granting task:read here would let the holder read the
# action items of the very meetings this policy hides. Rafael therefore gets 403 on the Action
# items page, which is the honest behaviour of the model as it exists today.
jq -n --arg t "$TENANT_CAMILA" '{
  version: "2026-05-07",
  statements: [
    { effect: "Allow",
      action: ["meeting:read"],
      resource: ["nora:tenant/\($t):meeting/*"],
      condition: { StringEquals: { region: "sul" } } }
  ]
}' >"$WORK/policy.json" || die "could not build the policy document"

jq -n --slurpfile doc "$WORK/policy.json" \
   '{name:"meetings-read-region-sul",
     description:"Read only the meetings whose region attribute is sul",
     document:$doc[0]}' >"$WORK/policy-request.json" \
  || die "could not build the policy request"

code=$(api POST /iam/policies "$TOKEN_CAMILA" -H 'Content-Type: application/json' \
        --data @"$WORK/policy-request.json")
case "$code" in 200|201) ;; *) die "POST /iam/policies returned $code. Body: $(body)" ;; esac
POLICY_ID=$(body_json '.id')
ok "policy meetings-read-region-sul = $POLICY_ID"

code=$(api POST "/iam/groups/$GROUP_ID/policies/$POLICY_ID" "$TOKEN_CAMILA")
case "$code" in 200|204) ;; *) die "attaching the policy returned $code. Body: $(body)" ;; esac
ok "policy attached to the group"

code=$(api POST /iam/users/invite "$TOKEN_CAMILA" -H 'Content-Type: application/json' --data @- <<JSON
{"email":"$EMAIL_RAFAEL","groupIds":["$GROUP_ID"],"expiresInDays":14}
JSON
)
case "$code" in 200|201) ;; *) die "POST /iam/users/invite returned $code. Body: $(body)" ;; esac
INVITE_ID=$(body_json '.id')
ok "invitation $INVITE_ID pending for $EMAIL_RAFAEL, carrying the group"

# ---------------------------------------------------------------------------
step "Enterprise meetings"
# ---------------------------------------------------------------------------
# The three arc meetings name the same customer throughout, because the account is resolved
# get-or-create by lower(name): a model that writes the name differently in two of them
# produces two accounts and no trend. Uploaded and awaited one at a time, in narrative order.
ARC_IDS=()
ARC=(
  "13-meridian-discovery-south-region.txt|Discovery — Central Log Transportes|2026-07-06T12:30:00Z"
  "14-meridian-objection-south-region.txt|Negociacao — Central Log Transportes|2026-07-24T17:00:00Z"
  "15-meridian-closing-south-region.txt|Fechamento — Central Log Transportes|2026-08-07T14:00:00Z"
)
for entry in "${ARC[@]}"; do
  IFS='|' read -r arc_file arc_title arc_start <<<"$entry"
  arc_id=$(upload_meeting "$TOKEN_CAMILA" "$DATA/meetings/$arc_file" \
            "$arc_title" "$arc_start" '{"region":"sul"}') || exit 1
  ARC_IDS+=("$arc_id")
  wait_for_analysis "$TOKEN_CAMILA" "$arc_id" && ok "$arc_file -> $arc_id (region=sul)"
done

M_SE=$(upload_meeting "$TOKEN_CAMILA" "$DATA/meetings/16-meridian-renewal-southeast-region.txt" \
        "Renovacao anual — Aurora Distribuidora" "2026-08-11T19:00:00Z" '{"region":"sudeste"}') || exit 1
wait_for_analysis "$TOKEN_CAMILA" "$M_SE" && ok "renewal -> $M_SE (region=sudeste)"

# No region attribute at all. The evaluator is fail-closed on a condition key missing from the
# context, so this one is invisible to the scoped policy without anyone writing a Deny.
M_INT=$(upload_meeting "$TOKEN_CAMILA" "$DATA/meetings/17-meridian-internal-planning.txt" \
        "Planejamento interno de implantacao" "2026-08-12T12:00:00Z" '{}') || exit 1
wait_for_analysis "$TOKEN_CAMILA" "$M_INT" && ok "internal -> $M_INT (no region attribute)"

# ---------------------------------------------------------------------------
step "Flows — one flow on the canvas"
# ---------------------------------------------------------------------------
# Created AFTER the uploads, on purpose: the three triggers are dispatched post-commit by the
# analysis round, so a flow that exists during seeding would fire once per seeded meeting. This
# one only runs when someone presses Test, or on the next analysis after the seed finishes.
#
# The recipient is on the seed's own domain, which defaults to an RFC 2606 reserved name. The
# send_email action sends for real through whatever EmailSender the API has — LogEmailSender in
# dev, Resend when RESEND_API_KEY is set — so the address has to be one that cannot reach a
# person if the second case is true.
jq -n --arg to "$EMAIL_CAMILA" '{
  name: "Risco detectado — avisar a diretoria comercial",
  active: true,
  definition: {
    nodes: [
      {id:"t1", kind:"trigger", type:"meeting.risk_detected", position:{x:0,y:0}},
      {id:"a1", kind:"action",  type:"send_email", params:{to:$to}, position:{x:240,y:0}}
    ],
    edges: [ {id:"e1", source:"t1", target:"a1"} ]
  }
}' >"$WORK/workflow.json" || die "could not build the workflow request"

code=$(api POST /workflows "$TOKEN_CAMILA" -H 'Content-Type: application/json' \
        --data @"$WORK/workflow.json")
case "$code" in
  200|201) FLOW_ID=$(body_json '.id'); ok "flow $FLOW_ID (trigger meeting.risk_detected)" ;;
  *) FLOW_ID=""; warn "POST /workflows returned $code — continuing without a flow. Body: $(body)" ;;
esac

# ---------------------------------------------------------------------------
step "Customer Confidence — what actually landed"
# ---------------------------------------------------------------------------
CONFIDENCE_FOUND=0
for id in ${ARC_IDS[@]+"${ARC_IDS[@]}"}; do
  api GET "/meetings/$id" "$TOKEN_CAMILA" >/dev/null
  account=$(body_json '.customerConfidence.accountName // empty')
  if [ -n "$account" ]; then
    CONFIDENCE_FOUND=$((CONFIDENCE_FOUND + 1))
    ok "$id account=$account score=$(body_json '.customerConfidence.score') band=$(body_json '.customerConfidence.band') trend=$(body_json '.customerConfidence.trend // "null"')"
  else
    warn "$id has no Customer Confidence"
  fi
done

if [ "$CONFIDENCE_FOUND" = "0" ]; then
  cat <<'MSG'

    No Customer Confidence was persisted for any of the three arc meetings. The overwhelmingly
    likely cause is a worker running with USE_LLM_STUB=true: the deterministic stub emits
    customerConfidence with accountName always null — it says so in stub_analyzer.py — and
    CustomerConfidenceService.persist is a documented no-op when accountName is null or blank.
    No account, no assessment, no trend.

    To get this block of the demonstration, run the worker against a real provider
    (USE_LLM_STUB=false plus LLM_API_KEY) and seed again with a different NORA_SEED_PREFIX.
MSG
elif [ "$CONFIDENCE_FOUND" -lt 3 ]; then
  warn "only $CONFIDENCE_FOUND of 3 arc meetings produced an assessment — the trend chain is incomplete"
fi

# ---------------------------------------------------------------------------
step "Enterprise member — Rafael Costa"
# ---------------------------------------------------------------------------
RAFAEL_DONE=0
if [ -n "${NORA_SEED_RAFAEL_TOKEN:-}" ]; then
  code=$(api POST "/iam/invites/${NORA_SEED_RAFAEL_TOKEN}/accept" - \
          -H 'Content-Type: application/json' -H 'X-NORA-Client: native' --data @- <<JSON
{"displayName":"Rafael Costa","password":"$PASSWORD"}
JSON
)
  if [ "$code" = "200" ]; then
    TOKEN_RAFAEL=$(body_json '.accessToken')
    ok "invitation accepted; Rafael is a member of tenant $TENANT_CAMILA"

    api GET /meetings "$TOKEN_RAFAEL" >/dev/null
    ok "GET /meetings as Rafael returns $(body_json '.totalItems // 0') of 5 (expected 3 — the region=sul arc)"

    code=$(api GET "/meetings/$M_SE" "$TOKEN_RAFAEL")
    [ "$code" = "403" ] && ok "the region=sudeste meeting answers 403" \
                        || warn "the region=sudeste meeting answered $code, expected 403"

    code=$(api GET "/meetings/$M_INT" "$TOKEN_RAFAEL")
    [ "$code" = "403" ] && ok "the meeting with no region attribute answers 403 (fail-closed)" \
                        || warn "the attribute-less meeting answered $code, expected 403"

    code=$(api GET /tasks "$TOKEN_RAFAEL")
    [ "$code" = "403" ] && ok "Action items answers 403 — the policy grants no task action, on purpose" \
                        || warn "GET /tasks answered $code; the seeded policy grants no task action"
    RAFAEL_DONE=1
  else
    warn "accepting the invitation returned $code. Body: $(body)"
  fi
else
  cat <<MSG

    NORA_SEED_RAFAEL_TOKEN is not set, so Rafael does not exist yet. Everything he needs is
    already created: the group, the scoped policy attached to it, and a pending invitation
    that carries the group. What is missing is only the acceptance.

    The raw invitation token is not obtainable from this machine. InvitationService stores only
    its SHA-256, no endpoint returns it, and LogEmailSender deliberately suppresses the link
    when it prints the dev e-mail. It exists in exactly one place: the invitation e-mail, which
    requires RESEND_API_KEY configured on the API.

    With that token in hand, and against a database that already holds this run:

        NORA_SEED_RAFAEL_TOKEN=<token> API_BASE=$API_BASE \\
          NORA_SEED_PASSWORD='$PASSWORD' NORA_SEED_PREFIX=$PREFIX scripts/seed-demo.sh

    That re-runs the whole seed, so use a fresh database or expect the signups to be refused.
    docs/challenge/demo-script.md block 4 describes what to present when this step never ran.
MSG
fi

# ---------------------------------------------------------------------------
step "summary"
# ---------------------------------------------------------------------------
if [ "$RAFAEL_DONE" = "1" ]; then INVITE_STATE=accepted; else INVITE_STATE=pending; fi
cat <<MSG

    Core workspace
      login       $EMAIL_LUCAS
      tenant      $TENANT_LUCAS
      meetings    $M_L1
                  $M_L2

    Enterprise workspace
      root        $EMAIL_CAMILA
      tenant      $TENANT_CAMILA
      group       $GROUP_ID (Comercial Sul)
      policy      $POLICY_ID (meetings-read-region-sul)
      invite      $INVITE_ID -> $EMAIL_RAFAEL ($INVITE_STATE)
      flow        ${FLOW_ID:-none} (meeting.risk_detected -> send_email)
      region=sul  ${ARC_IDS[0]:-none}
                  ${ARC_IDS[1]:-none}
                  ${ARC_IDS[2]:-none}
      other       $M_SE (region=sudeste)
                  $M_INT (no region attribute)

    Password for every seeded user: $PASSWORD
MSG
if [ "$GENERATED_PASSWORD" = "1" ]; then
  printf '    (generated for this run — set NORA_SEED_PASSWORD to choose your own)\n'
fi
printf '\n    Neither tenant can be deleted: there is no tenant-delete endpoint. To start over,\n'
printf '    reset the database (make db-reset) or run again with a different NORA_SEED_PREFIX.\n'
printf '\n%sDONE%s — %d steps, against %s\n' "$G" "$Z" "$STEP" "$API_BASE"
