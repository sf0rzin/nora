# SSH to the host over 443, through the existing Cloudflare Tunnel

Reaches the host from a network that blocks outbound port 22 — corporate firewalls with deep
packet inspection are the usual reason. The client speaks TLS to Cloudflare's edge on 443 and the
SSH stream rides inside it.

Everything here is **additive**. `sshd`, port 22 and the firewall are untouched, and the direct
path keeps working. That is deliberate: the direct path is the fallback while the new one is
being set up, and the way not to get locked out.

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
  not a meaningful gate in this mode. The protection that does exist is better: Cloudflare
  validates the rule server-side and pushes it to the connector **without a restart**, so adding
  a route cannot take `nora.systems` down.
- Routes are changed in the dashboard or through the Cloudflare API, and nowhere else.

## The mistake to avoid: `ssh://localhost:22`

The connector is a **container** on the `edge` bridge network. Its `localhost` is the container's
own loopback, where nothing listens on 22. A route to `ssh://localhost:22` authenticates fine and
then dies with `dial tcp 127.0.0.1:22: connect: connection refused`.

The service must be `ssh://host.docker.internal:22`, which requires the `extra_hosts` entry on
the cloudflared service in `docker-compose.yml`. The two go together.

Verified on 2026-08-10, from an ephemeral container on the same network:

```
$ docker run --rm --network nora_edge --add-host host.docker.internal:host-gateway \
    caddy:2.8-alpine sh -c 'getent hosts host.docker.internal; nc -w 5 host.docker.internal 22'
172.17.0.1        host.docker.internal
SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.18
```

`sshd` listens on `0.0.0.0:22` and `ufw` is inactive, so nothing blocks the bridge → host leg.

## Order of operations, and why

**Access application and policy first. Public hostname last.**

An `ssh://` route has no authentication of its own — it is sshd, exposed at a name anyone can
resolve. Until the Access policy exists, publishing the hostname is publishing an SSH endpoint to
the internet. Creating the application first means the name does not resolve at all while the
policy is being written, which is the safe order rather than the tidy one.

### 1. Access application

Zero Trust dashboard → **Access → Applications → Add an application → Self-hosted**:

| Field | Value |
|---|---|
| Application name | `NORA SSH` |
| Session duration | `24 hours` |
| Subdomain / Domain | `ssh` / `nora.systems` |
| Path | *(empty)* |

Then the policy: name `operator`, action **Allow**, rule **Include → Emails →** the operator's
address.

If Zero Trust has no login method yet, add **One-time PIN** (Settings → Authentication → Login
methods). It emails a code and needs no OAuth application. See `cloudflare-access.md` for
Google/GitHub.

Confirm the application is listed against `ssh.nora.systems` with one policy attached before
going on.

### 2. The compose change

`infra/host/docker-compose.yml` carries the `extra_hosts` entry. It reaches the host on the next
deploy that syncs the repo:

```bash
/opt/nora/infra/host/scripts/deploy.sh --sync --service cloudflared
```

This recreates the connector, which drops the tunnel for a few seconds. Do it deliberately, from
a session that is not the one you would need to recover, and confirm the site afterwards **from
outside**:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://nora.systems
curl -sS -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health
```

`at` is not installed on this host, so the scheduled-rollback idiom uses systemd instead:

```bash
# arm, BEFORE applying
sudo cp /opt/nora/infra/host/docker-compose.yml /root/compose.bak
sudo systemd-run --on-active=10min --unit=nora-cf-rollback /bin/bash -c \
  'cp /root/compose.bak /opt/nora/infra/host/docker-compose.yml && \
   /opt/nora/infra/host/scripts/deploy.sh --service cloudflared'

# ...apply, then verify from a SECOND connection and from outside...

# disarm
sudo systemctl stop nora-cf-rollback.timer && sudo systemctl reset-failed nora-cf-rollback.timer
```

### 3. Public hostname

Zero Trust → **Networks → Tunnels →** the NORA tunnel → **Configure → Public Hostnames → Add**:

| Field | Value |
|---|---|
| Subdomain | `ssh` |
| Domain | `nora.systems` |
| Type | **SSH** |
| URL | `host.docker.internal:22` |

The CNAME `ssh.nora.systems → <tunnel-id>.cfargotunnel.com` is created automatically. Do not also
create it by hand — two records for one name resolve inconsistently.

**Before the compose change of step 2 has been deployed**, `host.docker.internal` does not resolve
inside the connector. The working URL in the meantime is the `edge` network's gateway address
(`docker network inspect nora_edge -f '{{(index .IPAM.Config 0).Gateway}}'`, currently
`172.20.0.1`). That address is assigned by Docker and changes if the network is recreated, so it
is a stopgap and not the answer — switch the URL to `host.docker.internal:22` once step 2 lands.

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
`~/.cloudflared/` until the session expires.

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

- Remove the Public Hostname in the dashboard; the DNS record goes with it. Port 22 is unaffected.
- Revert `extra_hosts` and redeploy the connector. No other route depends on it.
- Nothing here modifies `sshd`, `ufw`/`nftables`, or the existing web routes.
