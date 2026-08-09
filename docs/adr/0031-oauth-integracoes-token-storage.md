# ADR 0031 — OAuth integrations (Google) and token storage

- **Status:** accepted
- **Date:** 2026-06-11
- **Deciders:** NORA Architect (pitch run) + Stratfy (PO, via GOAL.md)
- **Related:** ADR 0030 (workflow engine — the actions consume the connections), ADR 0002/0028
  (tenant isolation + RLS), ADR 0020 (token rotation precedent)

## Context

The star actions of NORA Flows (send an email via the user's Gmail, create an event in Google
Calendar, post to Slack) require REAL OAuth with external accounts. We need: a complete
authorization code flow, secure per-tenant token storage, automatic refresh at
runtime (the actions run in an asynchronous listener, with no user present) and a status hub
("Conectado"/"Conectar") on the integrations page.

Particularities: the OAuth callback arrives via a browser redirect on the API's domain (with no guarantee
of a session cookie); the Core is single-user (1 root user per tenant); the actions execute outside a
request (engine thread), so the token must be resolvable using only the event's tenant_id.

## Decision

1. **No Google SDK**: the flow is 2 POSTs (token endpoint) and 1 GET (userinfo) + 2 API calls
   (Gmail send, Calendar events) — `WebClient` directly in infrastructure adapters
   (`GoogleOAuthHttpClient`, `GoogleWorkspaceClient`), behind the ports `GoogleOAuthClient` (OAuth
   flow) and the `ActionExecutor` actions (`gmail_send_email`, `calendar_create_event`). Fewer
   dependencies, visible payloads, trivial stubbing in tests.
2. **Signed self-contained state (HMAC-SHA256)** instead of persisted state: it carries
   tenantId+userId+provider+exp(10min)+nonce, signed with `NORA_INTEGRATIONS_STATE_SECRET`
   (`OAuthStateCodec`). The callback is a PUBLIC route (`/integrations/*/oauth/callback`) — the state IS the
   credential: forgery breaks on the signature, a late replay on the expiration. The callback always REDIRECTS
   to the front end (`/integracoes?connected=…` or `?error=…`), it never answers JSON to the browser.
3. **Tenant-level connection** (`integration_connections`, V024): UNIQUE (tenant_id, provider) — the
   Core is single-user; `user_id` records who connected (audit). RLS enforced in the V022/V023 pattern.
   Reconnecting = upsert (ON CONFLICT) swapping tokens/account.
4. **Tokens encrypted at rest**: AES-256-GCM with a random IV per value (`TokenCipher`), a 32-byte key
   in `NORA_INTEGRATIONS_ENC_KEY`; format `enc:v1:iv:ciphertext`. WITHOUT a key (local dev),
   it writes `plain:…` with a WARN at boot — honest and visible degradation, never silent. The
   adapter encrypts/decrypts; the port and the domain speak tokens in the clear.
5. **Runtime refresh**: `IntegrationService.validGoogleAccessToken(tenantId)` renews when the
   access token is <60s from expiring, persists the rotation (keeping the current refresh token when
   Google does not send a new one — Google's default behavior) and returns a token ready for the action.
   An expired token WITHOUT a refresh token → a clear error asking for reconnection (it goes into the execution log).
6. **Minimal scopes**: `openid email` (to identify the account in the hub) + `gmail.send` (send only, no
   mailbox reading) + `calendar.events` (create events, without full access to the calendar).
   `access_type=offline&prompt=consent` to guarantee a refresh token on the first connection.

## Rejected alternatives

- **The official google-api-client SDK**: ~10 MB of transitive dependencies for 4 HTTP calls;
  it hides the flow we want to be auditable.
- **State persisted in a table**: requires a table + cleanup; the signed state is stateless and covers the
  same attacks in our flow (we do not use PKCE because the client is confidential — the secret is on the
  server).
- **Per-user token (not per tenant)**: correct in a multi-seat setup, but the Core is single-user; the
  Stratfy decision "Core sem IAM" makes the tenant-level connection simpler and sufficient. Upgrade
  trigger: multi-user tenants in Enterprise.
- **Key Vault for tokens**: latency+cost per workflow execution; AES-GCM with the key in an env var
  (which ALREADY comes from GitHub Secrets/KV at deploy time) gives encryption at rest with O(1) access.

## Consequences

- Slack will follow the same mold (a new provider in the enum + its own client + an action) — the hub
  (`GET /integrations`) already lists all providers with configured/connected.
- Revocation on Google's side (the user removes the app) shows up as a clear failure in the execution log
  (`ProviderError 400 invalid_grant`) — the hub keeps showing "Conectado" until reconnection or a manual
  disconnect; acceptable in the MVP.
- Proof: `IntegrationFlowIntegrationTest` (start → signed callback → connected status → a gmail action
  in a real workflow → disconnect → the action fails with a clear message; a forged state is rejected;
  cross-tenant isolation), `IntegrationServiceTest` (refresh/rotation), `OAuthStateCodecTest`,
  `TokenCipherTest`.
- Human handoff needed to actually activate it: a project in the Google Cloud Console, the consent
  screen, Client ID/Secret and redirect URIs (dev + api.nora.systems) — see `.env.example`.
