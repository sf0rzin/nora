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
# It marks the account verified directly instead of consuming a token. That is a smaller
# privilege than it sounds: it cannot create an account, cannot set a password and cannot
# authenticate. What it can do is confirm an address, which is why it refuses any address
# outside the reserved smoke domain — hard-coded, not a parameter, so no argument makes it
# touch a real user.
#
# Requires: root/docker access on the host, and the `nora-postgres` container running.
#
set -euo pipefail

SMOKE_DOMAIN="${NORA_SMOKE_EMAIL_DOMAIN:-smoke.invalid}"
PG_CONTAINER="${NORA_PG_CONTAINER:-nora-postgres}"
PG_USER="${POSTGRES_ADMIN_USER:-nora_admin}"
PG_DB="${POSTGRES_DB:-nora}"

addr="${1:-}"
[ -n "$addr" ] || { echo "usage: $(basename "$0") <email>" >&2; exit 2; }

# RFC 2606 reserved by default: an address there cannot receive mail, so an account created
# under it cannot belong to anyone. Anything else is refused, whatever the caller intended.
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
