# 0011 — Invite-based onboarding com restrição opcional de corporate domain

- Status: aceito
- Data: 2026-05-11
- Decisores: Stratfy (PO) + Claude Opus 4.7 (Tech Lead)

## Contexto

O MVP da NORA tem dois fluxos de onboarding declarados no backlog:

- **Core** (Lucas): self-signup com e-mail/senha (US01–US04). Já implementado.
- **Enterprise** (Camila/Rafael): o Root do tenant convida usuários por e-mail
  corporativo (US06). Sem self-signup. O audit pós-Subfase 1.0 confirmou que
  **US06 não tem código** (nenhuma migration, nenhum endpoint, nenhuma página).

Sem invite, o Root Enterprise hoje não consegue onboardar ninguém — workaround
seria criar usuários manualmente via banco, o que quebra a promessa do
produto (auditável, controlado, com permissões por IAM).

Junto disso, a US32 (corporate domain) está no Must Have e também ausente:
o Root quer restringir convites a um domínio (ex.: `acme.com`) para evitar
que sub-admins acidentalmente convidem e-mail pessoal de alguém externo. Os
dois — invite e domain restriction — são acoplados: o invite valida contra
o domain quando ele existe.

## Decisão

**Adotar invite-based onboarding com token de uso único e restrição opcional
de corporate domain.**

### Fluxo

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

### Configuração de domínio corporativo (US32)

- Coluna nova `tenants.allowed_email_domain VARCHAR(255) NULL` (default NULL = sem restrição).
- Endpoint `PUT /tenant/domain { allowedEmailDomain }` exige permissão IAM `tenant:domain:write`.
- Validação no servidor: regex `^[a-z0-9.-]+\.[a-z]{2,}$`, lowercase, sem prefixo `@`.
- Quando setado, `POST /iam/users/invite` rejeita email cujo domain não bate (HTTP 422 com código `EMAIL_DOMAIN_NOT_ALLOWED`).

### Status do invite

- `PENDING` — criado, ainda dentro do prazo
- `ACCEPTED` — aceito, user criado
- `EXPIRED` — passou de expiresAt sem aceite (transição via job ou check on-read)
- `REVOKED` — Root cancelou via `DELETE /iam/invites/{id}` antes de aceite

### Permissões IAM novas

| Action | Resource | Usado em |
|---|---|---|
| `iam:user:invite` | `nora:tenant/{tid}:invite/*` | criar invite |
| `iam:invite:read` | `nora:tenant/{tid}:invite/*` | listar invites |
| `iam:invite:revoke` | `nora:tenant/{tid}:invite/{id}` | revogar |
| `tenant:domain:write` | `nora:tenant/{tid}` | setar domain |
| `tenant:domain:read` | `nora:tenant/{tid}` | ler domain |

Root tem bypass por padrão (ver ADR 0007).

## Consequências

**Positivas:**

- Root tem controle granular sobre quem entra no tenant.
- Domain restriction protege contra convites acidentais a e-mails externos.
- Token revogável a qualquer momento antes do aceite.
- Auditável via `audit_events`: criação, aceite, expiração, revogação.
- Reusa `EmailSender` port existente (pra password reset) — sem dependência nova.
- Plugável: pra SSO futuro (US05), invite pode bypass set-password.

**Negativas / custos:**

- Fluxo de 2 etapas (convite + aceite) — 1 ponto de fricção a mais que self-signup.
- Depende de e-mail entregar — risco de spam folder ou MTA bouncing.
  Mitigação: status `PENDING` visível ao Root, possível "reenviar invite".
- Tokens em URL — risco de log/proxy expor. Mitigação: tokens são UUIDs longos,
  uso único, expiram em 7 dias, e a URL não contém PII.

## Alternativas Consideradas

1. **Magic link sem set password** — UX mais simples, mas força sessão sem
   senha verificável. Rejeitado: usuário Enterprise precisa autenticar em
   múltiplos dispositivos; senha + JWT é o padrão esperado pelo mercado B2B BR.

2. **Self-signup com domain whitelist (sem invite)** — qualquer e-mail
   `@empresa.com` pode criar conta. Rejeitado: perde controle do Root sobre
   quem entra, abre brecha pra ex-funcionário ainda com e-mail @empresa.com
   acessar o tenant. Quebra promessa Enterprise.

3. **SSO direto (Entra ID/SAML)** — Rejeitado pra MVP. Está no roadmap como
   US05 (pós-MVP). Quando vier, o invite continua válido pra usuários sem
   SSO ou pra fluxos onde IT exige convite explícito antes da federação.

4. **Convite sem domain restriction** — Rejeitado: US32 está no Must Have e
   é coupling natural com US06. Implementar separadamente dobra trabalho.

## Regras Acompanhantes

- Toda criação/aceite/revogação/expiração de invite gera `audit_event` com
  actor, target email, tenantId, timestamp.
- Convites expirados não são deletados — ficam em `iam_user_invitations`
  com status `EXPIRED` pra rastreabilidade histórica.
- Tokens nunca são logados em produção (PII).
- `acceptedUserId` permite rastrear quem o invite virou — útil pra
  auditoria pós-incidente ("quem convidou esse user e quando?").
- Quando `allowedEmailDomain` é setado em tenant que já tem usuários, isso
  **não revoga** usuários existentes — apenas restringe convites futuros.

Ver contratos exatos em `docs/api/examples/`:
- `iam-invite-request.json`, `iam-invite-response.json`, `iam-invite-list-response.json`, `iam-invite-accept-request.json`
- `tenant-domain-update-request.json`, `tenant-domain-update-response.json`
