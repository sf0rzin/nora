#!/usr/bin/env bash
#
# notify-failure.sh — the thing that says a systemd unit failed.
#
# WHY THIS EXISTS
# ---------------
# bootstrap-host.sh's own header carried the admission: a unit whose paths went stale "fails
# every interval with 200/CHDIR or 203/EXEC, and nothing in the stack alerts on a failed
# unit". That was true of the deploy timer, of the backup copy and of the restore drill —
# systemd knew, the journal recorded it, and no human found out until they went looking.
#
# Every unit this repository installs now carries `OnFailure=nora-alert@%n.service`, and that
# template unit runs this script with the failed unit's name as its only argument.
#
# WHY IT IS NOT GRAFANA'S ALERTING
# --------------------------------
# The rules under observability/grafana/provisioning/alerting/ cover the stack. This covers
# the machine, and the difference matters exactly when it is needed: a failed deploy unit is
# a plausible reason for Grafana itself not to be running. An alerting path that shares a
# failure domain with the thing it watches is a path that goes quiet at the worst moment.
#
# CONFIGURATION
#   NORA_ALERT_WEBHOOK_URL   (optional) endpoint that accepts a JSON POST. Read from
#                            /etc/nora/alerting.env by the unit's EnvironmentFile.
#
# With it unset the script prints to the journal and exits 0. That is deliberate: an alerting
# path that itself fails when unconfigured produces a second failed unit chasing the first,
# and OnFailure on THAT would be a loop. Fail quiet, but leave a line.
#
# Usage:  notify-failure.sh <unit-name> [extra message]
#
set -euo pipefail

UNIT="${1:-unknown.unit}"
EXTRA="${2:-}"
HOSTNAME_S="$(hostname 2>/dev/null || echo unknown-host)"
WEBHOOK="${NORA_ALERT_WEBHOOK_URL:-}"

# Matches the logfmt shape of run-backup.sh and offsite-backup.sh, so all three read the same
# way in `journalctl` and in Loki.
_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
logf() { local lvl="$1"; shift; printf 'ts=%s level=%s component=notify-failure %s\n' "$(_ts)" "$lvl" "$*"; }

TEXT="NORA on ${HOSTNAME_S}: systemd unit ${UNIT} FAILED."
[ -n "$EXTRA" ] && TEXT="$TEXT $EXTRA"
TEXT="$TEXT  Diagnose with: journalctl -u ${UNIT} -n 50 --no-pager"

logf error "event=unit.failed unit=${UNIT} host=${HOSTNAME_S}"

if [ -z "$WEBHOOK" ]; then
  logf warn "event=notify.unconfigured msg=\"NORA_ALERT_WEBHOOK_URL is not set in /etc/nora/alerting.env — this journal line is the whole alert\""
  exit 0
fi

# The payload is built by a JSON encoder where one is available rather than by string
# concatenation: a unit name is not attacker-controlled, but a quote or a backslash reaching a
# webhook as malformed JSON turns "the deploy failed" into "nothing happened".
export UNIT HOSTNAME_S
if command -v jq >/dev/null 2>&1; then
  PAYLOAD="$(jq -nc --arg text "$TEXT" '{text: $text, unit: $ENV.UNIT, host: $ENV.HOSTNAME_S}')"
else
  # jq is installed by bootstrap-host.sh, so this is the path for a host that predates it.
  # Escaping restricted to the two characters that can break a JSON string.
  ESCAPED="${TEXT//\\/\\\\}"
  ESCAPED="${ESCAPED//\"/\\\"}"
  PAYLOAD="{\"text\":\"${ESCAPED}\"}"
fi

if curl -fsS --max-time 20 -X POST "$WEBHOOK" \
     -H 'Content-Type: application/json' \
     -d "$PAYLOAD" >/dev/null 2>&1; then
  logf info "event=notify.sent unit=${UNIT}"
  exit 0
fi

# Non-zero would make THIS unit fail, and its own OnFailure would be the next thing to run.
# The journal line is the terminus.
logf error "event=notify.fail unit=${UNIT} msg=\"POST to NORA_ALERT_WEBHOOK_URL failed; the original failure is still in the journal\""
exit 0
