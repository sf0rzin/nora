# SSH to the host over 443, through the existing Cloudflare Tunnel

Reaches the host from a network that blocks outbound port 22 — corporate firewalls with deep
packet inspection are the usual reason. The client speaks TLS to Cloudflare's edge on 443 and the
SSH stream rides inside it.

**Status: live.** Applied on 2026-08-10 through the Cloudflare API. What exists:

| Thing | Value |
|---|---|
| Access application | `nora-ssh`, self-hosted, `ssh.nora.systems`, 24h session |
| Access policy | `operators-allowlist`, Allow, the same two operator e-mails that gate `admin.nora.systems` |
| Tunnel ingress rule | `ssh.nora.systems` → `ssh://172.17.0.1:22`, inserted before the `http_status:404` catch-all |
| DNS | `ssh.nora.systems` CNAME → `<tunnel-id>.cfargotunnel.com`, proxied |

Everything here is **additive**. `sshd`, port 22 and the firewall are untouched, and the direct
path keeps working. That is deliberate: the direct path is the fallback, and it is how you get
back in if the tunnel side breaks.

## Why not just move sshd to 443

A bare `sshd` on 443 sends `SSH-2.0-...` in cleartext as its first bytes. Deep packet inspection
compares protocol to port, finds SSH where TLS belongs, and drops the connection — that check is
the entire point of such an appliance. `cloudflared access ssh` opens genuine TLS to Cloudflare
and carries SSH inside a WebSocket, so on the wire it is HTTPS to a CDN, because it is.

## What this tunnel is, and what that rules out

The connector is **remotely managed**. `infra/host/docker-compose.yml` runs it as
`tunnel --no-autoupdate --metrics 0.0.0.0:2000 run` with `TUNNEL_TOKEN` and no `--config`, so it
fetches ingress from Cloudflare on connect.

Consequences worth stating, because each one is a wasted hour otherwise:

- `infra/host/cloudflared/config.yml` is **reference documentation**, not live configuration.
  Editing it and restarting changes no routing. Its own header says so.
- `cloudflared tunnel ingress validate` validates a local config file. There isn't one, so it is
  not a meaningful gate in this mode. What protects the site instead is better: Cloudflare
  validates the rule server-side and pushes it to the connector **without a restart**, so adding
  a route cannot take `nora.systems` down, and reverting is another API call rather than a
  redeploy.
- Routes are changed in the dashboard or through the Cloudflare API, and nowhere else.

## The mistake to avoid: `ssh://localhost:22`

The connector is a **container** on the `edge` bridge network. Its `localhost` is the container's
own loopback, where nothing listens on 22. A route to `ssh://localhost:22` authenticates fine and
then dies with `dial tcp 127.0.0.1:22: connect: connection refused`.

The route must name the host from the container's side. Verified from `nora-caddy`, which shares
both of the connector's networks:

```
$ docker exec nora-caddy sh -c 'nc -w 4 172.17.0.1 22'
SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.18
$ docker exec nora-caddy sh -c 'nc -w 4 172.20.0.1 22'
SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.18
```

`172.17.0.1` is the gateway of Docker's **default** bridge; `172.20.0.1` is `nora_edge`'s own.
Both reach the host, and the route uses `172.17.0.1` because it survives recreation of
`nora_edge`. `sshd` listens on `0.0.0.0:22` and `ufw` is inactive, so nothing blocks that leg.

`host.docker.internal` resolves to the same `172.17.0.1`, and is the version that does not depend
on Docker's address assignment at all — but it only resolves inside the container once the
`extra_hosts` entry on the cloudflared service in `docker-compose.yml` has been deployed. Switch
the route to `ssh://host.docker.internal:22` after that lands.

## Order of operations, and why

**Access application and policy first. DNS last.**

An `ssh://` route has no authentication of its own — it is sshd, exposed at a name anyone can
resolve. Until the Access policy exists, publishing the hostname is publishing an SSH endpoint to
the internet. Creating the application first means the name does not resolve at all while the
policy is being written, which is the safe order rather than the tidy one.

That is the order this was applied in, and the policy attachment was verified before the ingress
rule was written:

```
GET  /accounts/{acc}/access/apps/{app}/policies   ->  1 policy, decision=allow, 2 e-mails
```

## How it was applied (and how to redo it)

Credentials are Account API Tokens. Read them into a variable; never echo them.

```bash
ACC=$(cat .../cloudflare/id)      # account id
RW=$(cat .../cloudflare/write-all)
TUN=<tunnel-id>                   # GET /accounts/$ACC/cfd_tunnel?is_deleted=false
```

**1. Access application, with its policy inline.**

```bash
curl -sS -X POST "https://api.cloudflare.com/client/v4/accounts/$ACC/access/apps" \
  -H "Authorization: Bearer $RW" -H "Content-Type: application/json" -d '{
    "name": "nora-ssh", "type": "self_hosted", "domain": "ssh.nora.systems",
    "session_duration": "24h", "app_launcher_visible": false,
    "policies": [{ "name": "operators-allowlist", "decision": "allow",
      "include": [{"email":{"email":"..."}}, {"email":{"email":"..."}}] }]
  }'
```

Then **verify the policy attached** before going further. An application with zero policies
denies everyone, but an application that failed to create while the route exists is an open SSH
endpoint.

**2. Tunnel ingress — read, modify, write.** `PUT .../cfd_tunnel/$TUN/configurations` replaces
the whole config, so `GET` it first, keep every existing rule, insert the new one **before** the
`http_status:404` catch-all, and preserve `warp-routing`. Save the original as a rollback body
before writing.

**3. DNS.** `POST /zones/$ZONE/dns_records` with
`{"type":"CNAME","name":"ssh","content":"$TUN.cfargotunnel.com","proxied":true}`.

Note that the dashboard's "Add a public hostname" flow creates this record for you; the API path
does not. Do not do both — two records for one name resolve inconsistently.

**4. Verify, from outside.**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://nora.systems               # 200 — unaffected
curl -sS -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health   # 200
curl -sSI https://ssh.nora.systems | grep -i location                        # 302 to the Access login
```

A 302 to `<team>.cloudflareaccess.com/cdn-cgi/access/login/ssh.nora.systems` is the gate working.
Anything else — a 200, a connection reset — means the route is live without Access in front, and
the DNS record should be deleted immediately.

## Client

Install `cloudflared`:

```powershell
winget install --id Cloudflare.cloudflared
```

Connect:

```powershell
ssh -o ProxyCommand="cloudflared access ssh --hostname ssh.nora.systems" -i <path-to-key> ubuntu@ssh.nora.systems
```

Or, in `~/.ssh/config`:

```
Host nora
    HostName ssh.nora.systems
    User ubuntu
    IdentityFile C:\path\to\key
    IdentitiesOnly yes
    ProxyCommand cloudflared access ssh --hostname %h
    ServerAliveInterval 30
```

The first connection opens a browser for the Access login; the token is cached under
`~/.cloudflared/` until the session expires. The key path must be the **private key file**, not
the directory that contains it — `-i` on a directory fails with `Load key: Is a directory`.

## The failure mode to expect: corporate SSL inspection

An appliance doing full TLS interception presents its own CA, and `cloudflared` refuses it:

```
x509: certificate signed by unknown authority
websocket: bad handshake
```

Confirm by looking at who issued the certificate:

```powershell
openssl s_client -connect ssh.nora.systems:443 -servername ssh.nora.systems 2>$null | Select-String "issuer"
```

If it names the employer rather than Cloudflare or Google Trust Services, that is the cause. The
fix is to trust the corporate CA in the machine store:

```powershell
Import-Certificate -FilePath C:\path\to\corporate-ca.cer -CertStoreLocation Cert:\LocalMachine\Root
```

**Not `--no-tls-verify`.** That disables certificate validation altogether, so anything on that
network can impersonate Cloudflare's edge for the SSH session — it removes the property that
makes this path worth having.

## Rollback

In increasing order of severity, each independent of the others:

1. **Revoke access without removing anything:** delete the `operators-allowlist` policy from the
   `nora-ssh` application. Zero policies denies everyone.
2. **Remove the route:** delete the `ssh.nora.systems` DNS record, then `PUT` the tunnel
   configuration back to the saved rollback body. The site is unaffected either way — the other
   five rules and the catch-all are untouched.
3. **Remove everything:** also delete the `nora-ssh` Access application.

Nothing here modifies `sshd`, `ufw`/`nftables`, port 22, or the existing web routes, so none of
these steps can cost you the direct path or the site.
