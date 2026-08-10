# 0025 — Operator identity v2: Cloudflare Tunnel + Access (supersedes ADR 0023's Easy Auth)

- Status: accepted
- Date: 2026-06-01
- Related: ADR 0023 (operator identity v1 — partially superseded), ADR 0022 (platform database), `docs/operations/cloudflare-access.md`, `docs/operations/control-plane-runbook.md`

## Context

ADR 0023 defined the `nora-admin` edge as **Entra Easy Auth + `ipSecurityRestrictions`** ("both"). Creating the Entra group + App Registration is a manual step in the tenant.

At go-live (2026-06-01) this hit a hard blocker: the **fiap.com.br** tenant (managed by the institution) **denies** `az ad group create` / `az ad app create` with `Authorization_RequestDenied` — the owner account does not have the directory role (Application Developer / Groups Administrator). Without an App Registration, there is no Easy Auth.

In parallel, the Cloudflare lane had already delivered (PRs #177/#178) **Cloudflare Access** protecting `admin.nora.systems` (allowlist + OTP, central log, Free plan). But, as things stood, the origin (`nora-admin` with `ingress: external`) remained **reachable directly via Azure's raw FQDN**, bypassing Access — and with Easy Auth out and the IP allowlist empty, with no authentication at all.

## Decision

Replace Easy Auth (Entra) with **Cloudflare Tunnel + Cloudflare Access**, removing the public origin:

1. **No public origin.** `nora-admin` moves to `ingress: internal` (no public FQDN). External access is only via **Cloudflare Tunnel**: a **cloudflared** sidecar in the same Container App connects (outbound) to Cloudflare and forwards to Next on `localhost:3002`. The DNS `admin.nora.systems` becomes a CNAME (proxied) to `<tunnel-id>.cfargotunnel.com`. There is no Azure FQDN to bypass it with.
2. **Identity at the network edge:** Cloudflare Access (allowlist + OTP/SSO) gates `admin.nora.systems` before the request enters the tunnel.
3. **Identity at the app edge (Tier 2, defense-in-depth):** `nora-admin` validates the `Cf-Access-Jwt-Assertion` header (JWKS of the team domain + the Access App's `aud`) in a **server component** (Node runtime, `lib/access.ts`). No valid assertion → 403. It degrades to edge-only if `CF_ACCESS_TEAM_DOMAIN`/`CF_ACCESS_AUD` are not set.
4. **The token between services is unchanged** (inherited from ADR 0023): `nora-admin` → Spring `/admin/platform/**` with the admin token; `X-Operator-Email` (now from the `Cf-Access-Authenticated-User-Email` header) for auditing.
5. **Automation + lanes:** the tunnel is provisioned by the `cloudflare-tunnel.yml` workflow (idempotent, Cloudflare API, a **new file** — it does not touch the sibling lane's `cloudflare-setup.yml`). `cloudflare-setup.yml` remains the owner of the Access App/Policy/IdP and **must run without `admin_hostname`** (otherwise it overwrites the tunnel's CNAME). The tunnel lane was reassigned to the Control Plane architect by the PO.

## Consequences

**Positive:**
- Zero public surface on the admin (no Azure FQDN exposed) — stronger than v1's IP allowlist.
- No dependency on an Entra tenant (it works around the FIAP blocker). External operators (gmail/proton) get in via Access (OTP/SSO) without needing a B2B guest.
- Real defense in depth: network (Access) + transport (Tunnel, locked origin) + app (JWT validation) + service (admin token).
- Central access log in the Zero Trust panel. A good pitch argument (a genuine Zero Trust architecture).

**Negative / trade-offs:**
- `nora-admin` no longer scales to zero: the cloudflared connector needs ≥1 replica always up (~US$ 3–15/month of compute; order of magnitude ≤ ADR 0022's 2nd Postgres). Accepted.
- More pieces: sidecar + tunnel + workflow + secret (`CLOUDFLARE_TUNNEL_TOKEN`). Mitigated by idempotency + a runbook.
- JWT validation runs in a server component (not in middleware) because edge middleware would inline `CF_ACCESS_*` at build time. The gate runs per page render; `/healthz` (a route handler) naturally stays outside, preserving the Container App's probe.
- An operational dependency on Cloudflare at the edge. Accepted (it was already the edge chosen in 0023/cloudflare-access).

## Alternatives Considered

1. **Ask a FIAP tenant admin to create the App Registration** — rejected: a third-party dependency, friction, fragile for a demo.
2. **A separate (owner's) Entra tenant just for Easy Auth** — rejected: a throwaway tenant + B2B guests for the external operators; worse UX than Access's OTP/SSO.
3. **Cloudflare-only without removing the origin, locking it to Cloudflare's IP ranges** (an intermediate Tier 1) — it would lock the origin by IP instead of removing it. Rejected in favor of the Tunnel, which eliminates the entire public origin (stronger and a better pitch narrative).
4. **Keep `ingress: external` + cloudflared** — rejected: it would leave the raw FQDN accessible; the point is to have no public origin.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-06-01 | Control Plane Architect + Stratfy | Creation. Replaces ADR 0023's Easy Auth with Cloudflare Tunnel + Access after the FIAP tenant blocker (`Authorization_RequestDenied`). The PO reassigned the tunnel lane to the Control Plane architect. |
