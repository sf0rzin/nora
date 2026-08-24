#!/usr/bin/env bash
#
# smoke-confirm.sh — confirms a smoke account's e-mail address, on the host.
#
# `scripts/smoke-e2e.sh` can do everything over the public API except one step: confirming
# an address needs a token that exists only in the e-mail, and this deployment sends real
# mail (RESEND_API_KEY is set) with the token stored only as a SHA-256 hash. There is
# nothing to read back.
#
# So this is the deliberate out-of-band step, and the smoke script calls it through
# NORA_SMOKE_CONFIRM_CMD:
#
#   NORA_SMOKE_CONFIRM_CMD=/opt/nora/infra/host/scripts/smoke-confirm.sh \
#   API_BASE=https://api.nora.systems \
#   /opt/nora/scripts/smoke-e2e.sh
#
# It marks the account verified directly instead of consuming a token, and can also move a
# DISABLED or INVITED account to ACTIVE. It cannot create an account, cannot set a password
# and cannot authenticate — but it is more than "confirms an address", so the domain guard
# below is the only thing standing between it and a real user.
#
# That guard is a literal constant and not a variable, deliberately. An earlier version read
# it from NORA_SMOKE_EMAIL_DOMAIN, which meant the header's claim that it "cannot be pointed
# at a person" was false: NORA_SMOKE_EMAIL_DOMAIN=gmail.com and it would confirm any Gmail
# address. It happened to be safe only because sudo's env_reset strips the variable, which is
# a property of the caller and not of this script. If you need a different domain, edit this
# file in a commit somebody can read.
#
# Requires: root/docker access on the host, and the `nora-postgres` container running.
#
set -euo pipefail

# RFC 2606 reserves .invalid: an address here cannot resolve and cannot receive mail, so an
# account under it cannot belong to anyone. Not configurable — see above.
readonly SMOKE_DOMAIN="smoke.invalid"

PG_CONTAINER="${NORA_PG_CONTAINER:-nora-postgres}"
PG_DB="${POSTGRES_DB:-nora}"

# THE SUPERUSER IS ASKED FOR, NOT ASSUMED, and the reason is a smoke run that died on it.
#
# This line used to read `PG_USER="${POSTGRES_ADMIN_USER:-nora_admin}"`. `nora_admin` is the
# default in `infra/host/docker-compose.yml`, it is what `postgres/init/01-roles-and-db.sql`
# calls the owner, and it is the user every psql command in `docs/operations/host-deploy.md`
# passes. It is also not the role on the deployed host, whose data directory was initialised
# with POSTGRES_ADMIN_USER=postgres -- so on 2026-08-24 the documented end-to-end smoke died at
# step 4 with `FATAL: role "nora_admin" does not exist`, having proved signup and the
# verification gate and nothing after them.
#
# The environment could not save it either: this script runs from `NORA_SMOKE_CONFIRM_CMD`,
# usually under `sudo`, whose env_reset drops POSTGRES_ADMIN_USER even when the operator
# exported it. A default that is right in the repository and wrong on the host, in a script the
# host is the only place to run, is a default worth deleting.
#
# So: ask the container. A running Postgres knows which superuser its data directory was created
# with -- that is exactly what POSTGRES_USER holds inside it -- and it cannot disagree with
# itself the way a repository default can. An explicit POSTGRES_ADMIN_USER still wins, for the
# case where the container's own variable is absent; `nora_admin` stays as the last resort so a
# stopped container produces the old error rather than an empty `-U`.
PG_USER="${POSTGRES_ADMIN_USER:-}"
if [ -z "$PG_USER" ]; then
  PG_USER="$(docker exec "$PG_CONTAINER" printenv POSTGRES_USER 2>/dev/null || true)"
fi
PG_USER="${PG_USER:-nora_admin}"

addr="${1:-}"
[ -n "$addr" ] || { echo "usage: $(basename "$0") <email>" >&2; exit 2; }

# Anchored at the end: `case` matches the whole string and there is no trailing `*`, so
# `x@smoke.invalid.evil.com` and `x@smoke.invalidX` are both refused. Case-sensitive, so
# `x@SMOKE.INVALID` fails closed.
case "$addr" in
  *"@$SMOKE_DOMAIN") ;;
  *) echo "refusing to confirm '$addr': not under @$SMOKE_DOMAIN" >&2; exit 1 ;;
esac

# `and email_verified_at is null` makes this idempotent and keeps it from touching an account
# that some other path already confirmed.
updated=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -tAc \
  "update users
      set email_verified_at = now(),
          status            = 'ACTIVE',
          updated_at        = now()
    where email = '${addr//\'/\'\'}'
      and email_verified_at is null
  returning 1")

[ -n "$updated" ] || {
  echo "no unconfirmed user with address '$addr'" >&2
  exit 1
}
