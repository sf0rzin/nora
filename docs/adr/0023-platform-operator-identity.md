# 0023 — Operator identity (platform admin), separate from the per-tenant IAM

- Status: accepted (Easy Auth superseded by ADR 0025 — Cloudflare Tunnel/Access; the remaining decisions stand)
- Date: 2026-05-28
- Deciders: Co-architects (Opus) + Stratfy (PO/owner)
- Related: ADR 0007 (AWS-style per-tenant IAM), ADR 0022 (platform database), ADR 0025 (supersedes the Easy Auth part of this decision)

## Context

The control plane (ADR 0022) is operated **by the platform owners**, not by customers. The existing IAM
(ADR 0007: Root + Users + Groups + Policies) is **per-tenant** — NORA's JWT is tenant-scoped
(`tenantId` in the claim). Platform operators **do not belong to any tenant**; fitting them into the per-tenant
IAM would be conceptually wrong and dangerous (mixing planes).

There is no precedent in the repo for Entra Easy Auth or `ipSecurityRestrictions` — all current auth is our
own JWT (httpOnly cookies).

A product decision already settled with the owner: the operator console is a **separate Next app**
(`apps/admin`), not the same image as the API (NORA's UI is Next/React; serving admin in Thymeleaf would be
inconsistent in the pitch).

## Decision

Operator identity **completely separate** from the per-tenant IAM, with **isolation at the edge** and
**token-based authentication between services**:

1. **Edge (`nora-admin` Container App):** defense in depth with **Entra Easy Auth** (group
   "NORA Platform Admins") **AND** `ipSecurityRestrictions` (IP allowlist) on the ingress. Both.
   Only members of the group, coming from allowed IPs, reach the app.
2. **`nora-admin` (Next) is the only one that reads the operator's identity** (`X-MS-CLIENT-PRINCIPAL-*`
   injected by Easy Auth). It calls the Spring API **server-side** with:
   - `X-Internal-Token: <admin token>` (authorizes `/admin/platform/**`);
   - `X-Operator-Email: <email do operador>` (auditing — who changed what).
3. **The Spring API does not read the Easy Auth header.** It protects `/internal/platform/**` and `/admin/platform/**`
   with an **internal token** (`InternalTokenAuthFilter`, constant-time comparison), in their own security
   chains (`securityMatcher` + `@Order`), with the per-tenant JWT chain intact.
4. **Two tokens, least-privilege:** `NORA_PLATFORM_INTERNAL_TOKEN` (worker/BFF → `/internal/*`) and
   `NORA_PLATFORM_ADMIN_TOKEN` (nora-admin → `/admin/*`). Distinct by default; a leak of the worker's
   token does not grant access to admin mutations. Secrets in the Key Vault.
5. **The Entra group + App Registration are a MANUAL step** (Bicep does not create a group/app registration
   reliably). Documented in the runbook. Recommendation: an App Registration with "assignment required"
   + assigning **only** the group → only members receive a token from Easy Auth.

## Consequences

**Positive:**
- Separate planes: the owner-operator never goes through the per-tenant IAM; no risk of crossover.
- Defense in depth: network (IP) + identity (Entra) at the edge; a token between services.
- Spring stays simple: no Easy Auth parsing, no 2nd instance just to hide an endpoint. Auth for
  `/admin/*` is the admin token; the real isolation is at the `nora-admin` edge.
- The browser never talks directly to Spring (no extra CORS, no double Easy Auth).

**Negative / trade-offs:**
- `/admin/platform/**` on the public `nora-api` is reachable by anyone holding the admin token (the path is not
  hidden by the network). Accepted: the token is the gate, and the network/identity isolation is at the
  `nora-admin` edge. Future mitigation: restrict `/admin/*` to the internal origin.
- A manual step in Entra (group + app registration) — outside of IaC. Mitigated by a runbook.
- `X-Operator-Email` is trusted when the admin token is valid (it is not verified against Entra in
  Spring). Accepted: only `nora-admin` (behind Easy Auth, which strips client headers) has the admin
  token and sets that header.

## Alternatives Considered

1. **`nora-admin` = the same image as the API (Thymeleaf/JSON endpoints gated by env)** — rejected: it leaves
   the UI orphaned (NORA is Next), inconsistent in the pitch, and it would require a 2nd Spring instance just to hide an
   endpoint.
2. **Easy Auth header trust in Spring** — rejected: it couples Spring to Easy Auth, opens a risk of
   header spoofing on any ingress without Easy Auth in front, and duplicates the edge.
3. **The operator in the per-tenant IAM (a special "platform" tenant)** — rejected: it mixes planes, pollutes the
   tenant-scoped model, and exposes the control plane to the same blast radius as the customer's data.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-28 | Co-architects + Stratfy | Creation. Owner's refinement: `nora-admin` is a separate Next app; Spring via an internal token (not Easy Auth). A conscious exception to ADR 0014 authorized. |
| 2026-06-01 | Control Plane Architect + Stratfy | Easy Auth (Entra) replaced by **ADR 0025** (Cloudflare Tunnel + Access): the FIAP tenant blocked the creation of an App Registration (`Authorization_RequestDenied`). The token between services, the separation of planes (operator ≠ per-tenant IAM) and the `X-Operator-Email` for auditing remain in force. |
