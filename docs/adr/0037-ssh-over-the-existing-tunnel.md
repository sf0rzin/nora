# ADR 0037 — SSH reaches the host through the existing Cloudflare Tunnel, gated by Access

- **Status:** accepted
- **Date:** 2026-08-10
- **Extends:** ADR 0025 (Cloudflare Tunnel + Access as the operator edge) and ADR 0034 §2
  ("Ingress: Cloudflare Tunnel as the only entry path"). Neither is superseded: this adds a second
  *service* to the tunnel that already exists, and changes nothing about the web routes.
- **Related:** ADR 0036 (the substrate is one bare-metal host, so "the host" is a single machine
  with a single sshd), ADR 0017 (public repository, which is why the host address is not in it),
  `docs/operations/ssh-over-tunnel.md` (the runbook and the rollback),
  `infra/host/cloudflared/config.yml` (the versioned mirror of the ingress)

## Context

This record was written on 2026-08-11, after the fact. The decision was taken and executed on
2026-08-10 under time pressure, on a network that had just cut off access to the machine — so the
ADR documents a change that was already live, and the ordering argument in §2 below was reasoned
through during the change rather than before it. That is worth knowing when reading it.

Administration of this host is SSH on port 22. That is fine from a home network and useless from
a corporate one: an outbound filter that blocks 22 is common, and the network where this project
is actually worked on — a university campus behind a filtering appliance — blocks it. The
symptom that forced the decision was concrete: `Test-NetConnection <host> -Port 22` false, and
`github.com:22` false from the same machine, so `git push` failed too.

Two facts about the existing edge shaped what was available:

1. **A Cloudflare Tunnel already terminates all inbound HTTP** (ADR 0034 §2, which extended ADR
   0025 from `nora-admin` alone to the whole stack). No *web* port is published; `cloudflared`
   dials *out* and Cloudflare routes hostnames into it. Adding SSH is therefore adding a service
   to something already load-bearing, not opening a new door.

   The qualifier matters, because "the host publishes no port" is the shorthand this repository
   uses and it is not true of 22. Measured on the host on 2026-08-11: `ufw` **inactive**, iptables
   `INPUT` policy `ACCEPT` with no rule naming `dport 22`, and sshd listening on `0.0.0.0:22`. So
   port 22 is reachable from the whole internet, not from a LAN. §3 is where that fact does most of
   its work; Alternative 2 leans on it too.
2. **That tunnel is remotely managed.** `infra/host/docker-compose.yml` runs the connector with
   `TUNNEL_TOKEN` and no `--config`, so the ingress rules live in Cloudflare and are fetched on
   connect. This is a constraint, not a preference — see Consequences.

## Decision

**Publish `ssh.nora.systems` as an additional public hostname on the existing tunnel, routed to
the host's own sshd, with a Cloudflare Access application and an explicit operator allow-list in
front of it. Leave sshd, port 22 and the firewall untouched.**

### 1. Shape

| Piece | Value |
|---|---|
| Access application | `nora-ssh`, self-hosted, `ssh.nora.systems`, 24h session |
| Access policy | `operators-allowlist`, `decision: allow`, the same two operator e-mails that gate `admin.nora.systems` |
| Tunnel ingress rule | `ssh.nora.systems` → `ssh://172.17.0.1:22`, inserted before the `http_status:404` catch-all |
| DNS | `ssh` CNAME → `<tunnel-id>.cfargotunnel.com`, proxied |
| Client | `cloudflared access ssh` as an SSH `ProxyCommand` |

`172.17.0.1` is the gateway of Docker's **default** bridge, which the host also answers on. The
connector is not attached to that bridge — it is on `edge` and `internal`, and `nora_edge`'s own
gateway is `172.20.0.1` — but both addresses reach the host, and the route uses `172.17.0.1`
because it survives recreation of `nora_edge`. What must not be used is `ssh://localhost:22`: the connector
is a container, its `localhost` is its own loopback, and that route authenticates and then fails
with `connection refused`. It is the single most likely way to lose an hour here, so the runbook
says it in those words.

### 2. The order of operations is part of the decision

**Access application and policy first, DNS record last.**

An `ssh://` route carries no authentication of its own. It is sshd, published at a name anyone
can resolve. Two different things close the window, and which one is load-bearing depends on how
the hostname is registered:

- **Through the API**, which is how this was applied, ingress and DNS are separate calls. The
  CNAME is created last, so until then the hostname resolves to nothing and there is no interval
  in which an ungated route is reachable.
- **Through the dashboard**, "Add a public hostname" creates the DNS record for you — ingress and
  name appear together, and there is no CNAME-last step to rely on. There the pre-existing Access
  application is the *only* thing standing in front of sshd from the first moment the name
  resolves.

So the instruction is "Access first, DNS last" on both paths, but for different reasons, and an
earlier draft of this ADR gave only the first reason while claiming the Access application
"does not by itself protect anything". That is wrong: Access does not affect name *resolution*,
but it intercepts at the edge from the moment the name resolves, which is exactly the protection
the dashboard path depends on. `infra/host/cloudflared/config.yml` says the same in capitals.

One precondition neither this ADR nor the runbook had checked: the CNAME-last argument assumes
the zone has no wildcard record. Checked on 2026-08-11 — 13 records in `nora.systems`, six
proxied CNAMEs matching the six ingress rules one for one, **zero wildcards**. If a proxied `*`
record is ever added, the API path loses its safety property and only the Access-first ordering
remains.

The same asymmetry governs teardown, and it is the reverse: deleting the Access application while
the route and the record still exist removes the only authentication in front of sshd. The
rollback list in the runbook is therefore explicitly ordered, and says so.

### 3. Port 22 stays open, and closing it is a separate decision with its own preconditions

This change is **additive**. Nothing about sshd, `ufw` or the firewall was modified, and the
direct path on 22 works exactly as it did.

That is not laziness about finishing the job. The direct path is the recovery path: it is what
gets you in when the tunnel side breaks, and the tunnel side has more moving parts than sshd does.
It only works as a recovery path because 22 is reachable from anywhere rather than from a LAN, and
that is a measurement (see Context), not a design guarantee — no firewall enforces it. Re-check it
before relying on it. `docs/operations/host-deploy.md` used to provision `ufw default deny
incoming` with an allow rule scoped to `192.168.0.0/16`, which was never applied and which would
have locked out every operator on this machine; it now provisions `ufw allow 22/tcp`, and that is
not in effect either. The day a firewall is enabled here, this section is the one to revisit.

Closing 22 is worth doing eventually, and the preconditions are:

1. the tunnel route has survived a `cloudflared` restart **and** a host reboot, and
2. an out-of-band console from the hosting provider is confirmed to exist and to work.

Both are about the same scenario: the tunnel is the only way in, and it is down. Without (2) that
scenario is unrecoverable without physical access to the machine.

The cost of leaving it open is not zero and is worth stating with a number rather than a feeling.
Read from `sshd -T` on 2026-08-11, sshd is key-only — `passwordauthentication no`,
`kbdinteractiveauthentication no`, `permitrootlogin without-password` — and in the 24 hours to
that date it logged **1283** failed authentication attempts. That is background internet scanning finding an open 22, which is what an
open 22 gets. Key-only authentication is what makes it tolerable; it is not what makes it
invisible.

## Consequences

- **Reaching the host through the tunnel depends on Cloudflare being up** — not only the tunnel,
  also Access and the identity provider behind it. On a network that blocks 22 that is the *only*
  path, so on such a network administrative access does depend on all three. This is a real
  reduction in independence, accepted knowingly, because the alternative available there was no
  access at all. It is also exactly why §3 keeps port 22 open: from an unfiltered network the
  direct path makes the dependency a preference rather than a single point of failure. Both
  halves have to be said together — an earlier draft of this bullet said only the first and
  `CLAUDE.md` said only the second, which is how the two ended up asserting opposite things.
- **A revoked or expired Access session costs an interactive login.** The token is cached under
  `~/.cloudflared/` for the session duration; when it lapses, `cloudflared` opens a browser and
  waits for a one-time code delivered by e-mail. That is correct behaviour for an authentication
  gate, and it means an unattended process cannot silently reconnect after a revocation. Observed
  in exactly that form during this work.
- **The live routing is not in this repository.** Remotely-managed means Cloudflare holds the
  ingress. `infra/host/cloudflared/config.yml` is a versioned *mirror* of it — reviewable in a
  pull request, usable to rebuild the tunnel from scratch — and its header says in as many words
  that editing it changes nothing. The trade-off is stated in that file's own footer — remotely
  managed "keeps only the `TUNNEL_TOKEN`, at the cost of the real routing living outside the repo"
  — and this ADR inherits it, adding one more rule to the set that has to be kept in sync by hand.
- **`cloudflared tunnel ingress validate` does not gate what actually ships.** It validates a
  local config file. One exists — `infra/host/cloudflared/config.yml` is a complete, valid
  cloudflared config — but the connector does not read it, so validating it proves the *mirror* is
  well formed and says nothing about the rules Cloudflare will serve. That is still worth doing,
  precisely because the mirror is maintained by hand; what it is not is a pre-flight check on a
  routing change.

  What Cloudflare gives instead is that a rule is validated server-side and pushed to the
  connector without a restart, so a route change needs no redeploy and reverts with another API
  call. That is **not** the same as "a route change cannot take the site down": the configuration
  endpoint is a whole-document `PUT`, so a read-modify-write that loses a rule is perfectly valid
  and removes it. The runbook demands the original be saved as a rollback body before writing for
  exactly that reason.
- **The compose change that would make the route independent of Docker's addressing is not free.**
  `extra_hosts: host.docker.internal:host-gateway` on the connector only takes effect when the
  container is **recreated**, and recreating the connector drops the only ingress for a few
  seconds — there is nothing in front of it the way Caddy is in front of everything else. So the
  route names the IP until that is done deliberately, from a session that is not the one you would
  need to recover. The procedure, with an armed rollback, is in the runbook.
- **The host's address stays out of the repository** (ADR 0017: it is public). The tunnel does not
  change that; if anything it helps, since the name now published is a Cloudflare hostname rather
  than an origin.

## Alternatives Considered

1. **Move sshd to port 443.** Rejected on an expectation, and the expectation is worth labelling
   as one because it was never tested: a bare sshd sends `SSH-2.0-...` in cleartext as its first
   bytes, and an appliance doing deep packet inspection compares protocol to port, finds SSH where
   TLS belongs, and drops the connection. That is what such appliances are for. What was actually
   *observed* on the blocking network is a different mechanism — full TLS interception, which
   `cloudflared` reported as `x509: certificate signed by unknown authority` — and an appliance
   that terminates and re-signs 443 is not thereby proven to protocol-police it. So this is the
   reasoned expectation, not a measurement, and it stays rejected on the weaker ground that it
   moves the administrative port to the one port the network scrutinises most.
2. **Run sshd on 443 behind a TLS wrapper (stunnel, sslh).** Rejected. It reaches the same place
   as the accepted option — SSH inside TLS on 443 — but by adding a component to the host, giving
   it a certificate to renew, and publishing a second port. The tunnel already terminates TLS for
   this host and already has an authentication layer in front of it.
3. **Cloudflare WARP with a private network route.** Rejected for this use, kept as the better
   answer to a different question. WARP routes a CIDR rather than publishing a hostname, which is
   the right shape for reaching several services on a private network. Here there is one service
   on one host, and WARP requires a client daemon with device enrolment on every machine that
   needs access, on networks that may well block the WARP transport too. `cloudflared access ssh`
   as a `ProxyCommand` needs one binary and no privileged install.
4. **A second, dedicated tunnel for SSH.** Rejected. Two connectors mean two tokens, two sets of
   ingress to keep straight, and a second thing that can be up while the other is down. The
   existing tunnel already has the property that matters — it is the only ingress and it is
   monitored — and a hostname is the unit of separation Cloudflare already gives us.
5. **A general-purpose VPN (WireGuard, Tailscale).** Rejected as disproportionate rather than
   wrong. It is a good answer for a fleet; here it adds a second remote-access system, with its
   own identity story, alongside an Access tenancy that already exists and already gates the
   operator console with the same two e-mail addresses. Worth revisiting if the number of hosts
   ever stops being one.
6. **Leave it, and administer the host only from an unfiltered network.** Rejected once it was
   the actual situation. The work does not happen only at home, and "wait until you are on a
   different network" is not an operational answer when the reason to log in is that something
   is broken.
