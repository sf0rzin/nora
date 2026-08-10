# 0011 — Invite-based onboarding with optional corporate domain restriction

- Status: accepted
- Date: 2026-05-11

## Context

The NORA MVP has two onboarding flows declared in the backlog:

- **Core** (Lucas): self-signup with email/password (US01–US04). Already implemented.
- **Enterprise** (Camila/Rafael): the tenant's Root invites users by corporate
  email (US06). No self-signup. The post-Sub-phase 1.0 audit confirmed that
  **US06 has no code** (no migration, no endpoint, no page).

Without invites, the Enterprise Root currently cannot onboard anyone — the workaround
would be to create users manually via the database, which breaks the product's
promise (auditable, controlled, with IAM permissions).

Alongside that, US32 (corporate domain) is in the Must Have and is also absent:
the Root wants to restrict invites to a domain (e.g., `acme.com`) to prevent
sub-admins from accidentally inviting someone external's personal email. The
two — invite and domain restriction — are coupled: the invite validates against
the domain when it exists.

## Decision

**Adopt invite-based onboarding with a single-use token and an optional corporate
domain restriction.**

### Flow

```
Root (ou user com iam:user:invite)
  │
  │ POST /iam/users/invite { email, groupIds[], expiresInDays }
  ▼
Backend valida:
  - permissão IAM (action: iam:user:invite)
  - se tenant tem allowedEmailDomain configurado, email deve bater
  - groupIds existem e pertencem ao tenant
  │
  ▼
Backend cria invite em iam_user_invitations:
  - token UUID v4 (secret, never returned in API responses)
  - status PENDING, expiresAt = now + expiresInDays (default 7)
  - audit_event "iam.user.invited" gravado
  - Email enviado via EmailSender port (Resend / SendGrid / SMTP)
  │
  ▼
Convidado clica link "https://nora.app/invites/accept/{token}"
  │
  ▼
Frontend exibe form: displayName + password
  │
  │ POST /iam/invites/{token}/accept { displayName, password }
  ▼
Backend valida token:
  - existe, status PENDING, expiresAt > now
  - cria user no tenant (PasswordHasher.hash)
  - anexa user aos groupIds do invite
  - marca invite ACCEPTED, gravar acceptedUserId e acceptedAt
  - audit_event "iam.invite.accepted"
  - retorna JWT (login automático)
```

### Corporate domain configuration (US32)

- New column `tenants.allowed_email_domain VARCHAR(255) NULL` (default NULL = no restriction).
- Endpoint `PUT /tenant/domain { allowedEmailDomain }` requires the IAM permission `tenant:domain:write`.
- Server-side validation: regex `^[a-z0-9.-]+\.[a-z]{2,}$`, lowercase, without the `@` prefix.
- When set, `POST /iam/users/invite` rejects an email whose domain does not match (HTTP 422 with code `EMAIL_DOMAIN_NOT_ALLOWED`).

### Invite status

- `PENDING` — created, still within the deadline
- `ACCEPTED` — accepted, user created
- `EXPIRED` — passed expiresAt without acceptance (transition via job or check on-read)
- `REVOKED` — Root cancelled it via `DELETE /iam/invites/{id}` before acceptance

### New IAM permissions

| Action | Resource | Used in |
|---|---|---|
| `iam:user:invite` | `nora:tenant/{tid}:invite/*` | create invite |
| `iam:invite:read` | `nora:tenant/{tid}:invite/*` | list invites |
| `iam:invite:revoke` | `nora:tenant/{tid}:invite/{id}` | revoke |
| `tenant:domain:write` | `nora:tenant/{tid}` | set domain |
| `tenant:domain:read` | `nora:tenant/{tid}` | read domain |

Root has bypass by default (see ADR 0007).

## Consequences

**Positive:**

- The Root has granular control over who joins the tenant.
- Domain restriction protects against accidental invites to external emails.
- The token is revocable at any time before acceptance.
- Auditable via `audit_events`: creation, acceptance, expiration, revocation.
- Reuses the existing `EmailSender` port (for password reset) — no new dependency.
- Pluggable: for future SSO (US05), the invite can bypass set-password.

**Negative / costs:**

- A 2-step flow (invite + acceptance) — 1 more friction point than self-signup.
- Depends on email delivery — risk of the spam folder or MTA bouncing.
  Mitigation: `PENDING` status visible to the Root, possible "resend invite".
- Tokens in the URL — risk of a log/proxy exposing them. Mitigation: tokens are long UUIDs,
  single-use, expire in 7 days, and the URL contains no PII.

## Alternatives Considered

1. **Magic link without set password** — simpler UX, but it forces a session without
   a verifiable password. Rejected: the Enterprise user needs to authenticate on
   multiple devices; password + JWT is the standard expected by the Brazilian B2B market.

2. **Self-signup with a domain whitelist (no invite)** — any `@empresa.com`
   email can create an account. Rejected: it loses the Root's control over
   who joins, and opens a gap for a former employee still holding an @empresa.com email
   to access the tenant. It breaks the Enterprise promise.

3. **Direct SSO (Entra ID/SAML)** — Rejected for the MVP. It is on the roadmap as
   US05 (post-MVP). When it arrives, the invite remains valid for users without
   SSO or for flows where IT requires an explicit invite before federation.

4. **Invite without domain restriction** — Rejected: US32 is in the Must Have and
   it is a natural coupling with US06. Implementing them separately doubles the work.

## Accompanying Rules

- Every invite creation/acceptance/revocation/expiration generates an `audit_event` with
  actor, target email, tenantId, timestamp.
- Expired invites are not deleted — they remain in `iam_user_invitations`
  with status `EXPIRED` for historical traceability.
- Tokens are never logged in production (PII).
- `acceptedUserId` makes it possible to trace who the invite became — useful for
  post-incident auditing ("who invited this user and when?").
- When `allowedEmailDomain` is set on a tenant that already has users, this
  **does not revoke** existing users — it only restricts future invites.

See the exact contracts in `docs/api/examples/`:
- `iam-invite-request.json`, `iam-invite-response.json`, `iam-invite-list-response.json`, `iam-invite-accept-request.json`
- `tenant-domain-update-request.json`, `tenant-domain-update-response.json`
