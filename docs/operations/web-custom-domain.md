---
title: "Runbook — custom domain for the web app (nora.systems)"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-08
relacionado: "ADR 0025 (admin via Cloudflare Tunnel — distinct surface); docs/operations/cloudflare-access.md"
---

# Runbook — custom domain for the web app (`nora.systems`)

> Wires the root domain `nora.systems` and `www.nora.systems` to the web app (`apps/web`),
> served by the Azure Container App `nora-web-dev`, with Cloudflare in front (proxied:
> WAF/DDoS + origin IP hidden) and a TLS certificate managed by Azure.

Unlike `admin.nora.systems` (which has no public ingress and comes in through a **Cloudflare
Tunnel**, ADR 0025), the web app **does have public ingress** on Azure. That is why the path is
simpler: proxied DNS pointing at the app + a custom domain with a managed certificate.

## Outcome

- `https://nora.systems` → web app (HTTP 200, valid TLS), via Cloudflare
- `https://www.nora.systems` → same
- `admin.nora.systems` remains untouched (its own tunnel)

## Prerequisites

- **Azure CLI** logged in with the `Contributor` role on `rg-nora-dev`.
- **Cloudflare DNS access** (zone `nora.systems`): via the `cf-api` vault (skill
  `sforzin-setup`, DNS:Edit token) or an equivalent token / the dashboard.
- `jq` to format the API responses.

## Reference values for this environment

| Item | Value | How to obtain it |
|---|---|---|
| Resource group | `rg-nora-dev` | — |
| Container App (web) | `nora-web-dev` | `az containerapp list -g rg-nora-dev -o table` |
| Managed environment | `nora-cae-dev` | `az containerapp env list -g rg-nora-dev -o table` |
| App's default FQDN | `nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` | `az containerapp show -g rg-nora-dev -n nora-web-dev --query properties.configuration.ingress.fqdn -o tsv` |
| Environment static IP | `20.236.215.95` | `az containerapp env show -g rg-nora-dev -n nora-cae-dev --query properties.staticIp -o tsv` |
| `customDomainVerificationId` (the `asuid` value) | `D07F1728…A1DF6` | `az containerapp env show -g rg-nora-dev -n nora-cae-dev --query properties.customDomainConfiguration.customDomainVerificationId -o tsv` |
| Cloudflare zone id | `02a6a502…b8500` | `cf-api 'https://api.cloudflare.com/client/v4/zones?name=nora.systems' \| jq -r '.result[0].id'` |

## Sequence (state)

| Step | Action | Proxy state | Status |
|---|---|---|---|
| 1 | `asuid` TXT (apex + www) | n/a (TXT) | Done |
| 2 | apex/www → Azure (CNAME) | DNS-only during provisioning | Done |
| 3 | Azure: `hostname add` + `bind` (cert) | — | Done |
| 4 | Verify directly (DNS-only) | DNS-only | Done |
| 5 | Flip to proxied (flattening) | proxied | Done |
| 6 | Verify via Cloudflare | proxied | Done |

## Steps

> The `cf-api` calls run server-side through the vault (the key never leaves the VM). The
> records are idempotent: re-running reconciles them. `$ZONE` = zone id; `$VERID` =
> `customDomainVerificationId`; `$FQDN` = the app's default FQDN.

### 1. Domain ownership verification (`asuid` TXT)

Create a TXT record `asuid` (apex) and `asuid.www` with the environment's `customDomainVerificationId`:

```bash
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"TXT\",\"name\":\"asuid\",\"content\":\"$VERID\",\"ttl\":1}"
# repetir com "name":"asuid.www"
```

### 2. Point apex and www at Azure (DNS-only during provisioning)

The apex uses **CNAME flattening** (CNAME at the root → Azure FQDN; Cloudflare resolves it as
an A record at the edge). Keep it **DNS-only (grey)** until the certificate is issued — Azure needs
to see the domain pointing at it during validation.

```bash
# apex: substituir o registro existente da raiz por CNAME -> FQDN, proxied=false
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$APEX_ID" -X PUT \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"CNAME\",\"name\":\"@\",\"content\":\"$FQDN\",\"proxied\":false,\"ttl\":1}"
# www: CNAME -> FQDN, proxied=false  (substitui o parking da Namecheap, se houver)
```

### 3. Azure — add the hostname and issue the managed certificate

Use **HTTP validation** (`--validation-method HTTP`). TXT validation via `az
containerapp` is semi-manual (it prints a token and waits for an additional TXT record in a second phase)
and fails when run in one go; HTTP validation is automatic — Azure serves the challenge on the IP
that DNS already points to.

```bash
az containerapp hostname add  -g rg-nora-dev -n nora-web-dev --hostname nora.systems
az containerapp hostname bind -g rg-nora-dev -n nora-web-dev --hostname nora.systems \
  -e nora-cae-dev --validation-method HTTP
# repetir para www.nora.systems
```

The expected result per hostname is `bindingType: SniEnabled` with a `certificateId`. Issuance
takes from 1 to a few minutes.

> If a certificate gets stuck in `Pending` (e.g. a previous attempt via TXT), delete it
> before retrying:
> `az containerapp env certificate list -g rg-nora-dev -n nora-cae-dev --managed-certificates-only`
> and `az containerapp env certificate delete -g rg-nora-dev -n nora-cae-dev --certificate <nome> --yes`.

### 4. Verify against the direct origin (still DNS-only)

```bash
curl -sS -o /dev/null -w "http=%{http_code} ssl=%{ssl_verify_result}\n" https://nora.systems
# Esperado: http=200 ssl=0 (cert válido). Se houver atraso de propagação, repita.
```

### 5. Flip to proxied (Cloudflare in front)

With the certificate issued, turn the proxy on (orange). The apex stays as a proxied CNAME→FQDN
(flattening); `www` likewise.

```bash
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$APEX_ID" -X PUT \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"CNAME\",\"name\":\"@\",\"content\":\"$FQDN\",\"proxied\":true,\"ttl\":1}"
# www: idem com proxied=true
```

The zone's SSL/TLS mode must be **Full** (or Full strict). With Full, Cloudflare connects
to the origin over HTTPS and accepts Azure's managed certificate (which is public and valid).

### 6. Verify via Cloudflare

```bash
curl -sS -I https://nora.systems | grep -iE "^(server|cf-ray):"
# Esperado: Server: cloudflare + CF-RAY presente, e HTTP 200 no corpo.
```

## Final smoke test

- `https://nora.systems` and `https://www.nora.systems` return 200 served by the app
  (header `x-powered-by: Next.js`), via Cloudflare.
- `https://admin.nora.systems` keeps responding (302 → Cloudflare Access login).

## Rollback

- **Revert DNS:** point apex/www back at the previous target (or `proxied:false`)
  via `cf-api ... -X PUT`.
- **Remove the custom domain from Azure:**
  `az containerapp hostname delete -g rg-nora-dev -n nora-web-dev --hostname nora.systems --yes`
  (same for www). The app's default FQDN keeps working.

## Caution — recommended improvements (known debt)

1. **Certificate renewal behind the proxy.** Azure's managed certificate renews
   through HTTP validation; with the domain proxied, Cloudflare's "Always Use HTTPS" option can
   intercept the HTTP-01 challenge and **break automatic renewal**. The current certificate
   is valid for ~6 months. **Durable fix:** install a **Cloudflare Origin Certificate** (valid for
   15 years) on Azure as a bring-your-own certificate and use SSL "Full (strict)" — this eliminates the
   dependency on Azure's renewal. It requires a Cloudflare token with `SSL:Edit` (the vault's
   is only `DNS:Edit`).
2. **Zone SSL/TLS = Full → Full (strict).** Azure's certificate is public and valid,
   so strict mode is safe and recommended. Requires `Zone Settings:Edit`.
3. **Canonical `www → apex` redirect.** Today both serve the app (duplicate content, per-host
   session). Making the apex canonical and redirecting `www` avoids split cookies/sessions.
   Requires `Page Rules` or `Rulesets` (Dynamic Redirects) on the token.

## Notes

- The DNS automation uses the `cf-api` vault (skill `sforzin-setup`); the Cloudflare key never
  leaves the VM. Alternative: the Cloudflare dashboard.
- `admin.nora.systems` is a separate surface (Cloudflare Tunnel + Access, ADR 0025) and
  is not affected by this runbook.

## Document history

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0 | 2026-06-08 | NORA Architect (Tech Lead) | Created after wiring `nora.systems`/`www` to the web app |
