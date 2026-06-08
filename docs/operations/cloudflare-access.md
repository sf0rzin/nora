---
title: "Cloudflare Access — proteção do console operador"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Cloudflare Access — proteção do console operador

Runbook para configurar e operar Cloudflare Access protegendo `admin.nora.systems` (console do nora-admin). Atualizado em 2026-06-02 pós-ADR 0025.

> **Atenção — Modelo atual (ADR 0025): Cloudflare Tunnel + Access.** O `nora-admin` deixou de ter ingress público — o único caminho de entrada é via Cloudflare Tunnel (sidecar `cloudflared`). O CNAME `admin.nora.systems` aponta para `<tunnel-id>.cfargotunnel.com`, **não para o Azure**. Por isso:
>
> - **Este workflow (`cloudflare-setup.yml`)** é dono do **Access App + Policy + IdP**.
> - **Workflow irmão (`cloudflare-tunnel.yml`)** é dono do **CNAME + Tunnel + rota**.
> - **NUNCA** passar `admin_hostname` no `cloudflare-setup.yml` — o step de CNAME tem guard que ABORTA se detectar o tunel. Input mantido apenas para back-compat.
>
> Para túnel e ingress, ver `docs/operations/control-plane-runbook.md` e ADR 0025.

## Por que Cloudflare Access

O console do operador (`nora-admin`) é a porta de entrada do control plane (ADR 0022/0023/0024). Easy Auth via Entra cobre autenticação, mas:

- Depende de App Registration manual + grupo Entra (passo demorado, ver `control-plane-runbook.md`)
- Não dá log central de tentativas de acesso
- Não oferece login com identidades externas (Google/GitHub) sem ADR adicional

Cloudflare Access cobre essas três frentes na borda, **antes** da requisição chegar no Container App (ver ADR 0025 — a identidade do operador na borda migrou de Easy Auth/Entra para Cloudflare Tunnel + Access):

- Login obrigatório por email (One-Time PIN por padrão; Google + GitHub opcionais)
- Allowlist explícita (sem grupos Entra)
- Log central de tentativas/denials no painel Zero Trust
- Plano Free até 50 usuários

Easy Auth + Cloudflare Access coexistem: Cloudflare protege na borda da rede, Easy Auth protege na borda do app. Defesa em profundidade.

## Estado atual

| Item | Valor |
|---|---|
| Domínio | `nora.systems` (registrar: Namecheap; nameservers: Cloudflare) |
| Account ID | `76e6b917a3ef8fcaff2b20ffb2c8afd6` |
| Hostname protegido | `admin.nora.systems` |
| Identity provider default | One-Time PIN (email) |
| Allowlist inicial | `axonogenesis@proton.me`, `gmaciel0204@gmail.com` |

## Configuração inicial (uma vez)

### 1. Habilitar Zero Trust na conta CF

Passo manual no dashboard, **não tem API para criar team**:

1. `https://one.dash.cloudflare.com/`
2. Escolhe a conta de `nora.systems`
3. Onboarding pede team name (vira `<name>.cloudflareaccess.com`) — sugestão: `stratfy`
4. Escolhe plano **Free** (até 50 users, sem expiração)
5. Cartão de crédito é solicitado mas não cobra até passar de 50 users

### 2. Criar API token escopado

`dash.cloudflare.com` → My Profile → API Tokens → Create Custom Token.

**Permissões mínimas (caminho crítico — DNS + Access App + Policy funcionam):**

- `Account` → `Access: Apps and Policies` → **Edit**
- `Account` → `Access: Service Tokens` → **Edit**
- `Zone` → `DNS` → **Edit**
- `Zone` → `Zone` → **Read**

**Permissão opcional (full automation, inclui criar IdP via workflow):**

- `Account` → `Access: Organizations, Identity Providers, and Groups` → **Edit**

Sem a permissão opcional, o workflow degrada graciosamente: `team_domain` é construído do input (não verificado via API) e OTP IdP não é criado — usuário adiciona manualmente em 30s pelo painel (ver "Operação → Adicionar OTP IdP manualmente" abaixo).

**Scope:**

- Account Resources: conta específica
- Zone Resources: zona específica (`nora.systems`)

Cola o token como secret `CLOUDFLARE_API_TOKEN` no repo (`Settings → Secrets and variables → Actions`).

### 3. Rodar o workflow

`.github/workflows/cloudflare-setup.yml` é `workflow_dispatch` e idempotente. Inputs:

- `admin_hostname` — **LEGACY pré-ADR 0025. DEIXE VAZIO.** O CNAME hoje é gerenciado pelo `cloudflare-tunnel.yml`. O step tem guard que aborta se detectar `*.cfargotunnel.com` no CNAME existente (proteção contra derrubar o admin por engano).
- `team_name` — nome do team (default `stratfy`); precisa coincidir com o team já criado no passo 1
- `access_emails` — CSV de emails autorizados (default já tem os 2 operadores)

O workflow faz upsert: garantir Access App, garantir policy de allowlist, garantir OTP IdP. Re-rodar é seguro.

Pra configurar/reconciliar o **túnel** em si (CNAME → cfargotunnel + rota → sidecar), rodar o `cloudflare-tunnel.yml` (ver `control-plane-runbook.md`).

## Provedores de identidade — adicionar Google e/ou GitHub

OTP por email funciona sem nenhum setup adicional. Google/GitHub OAuth precisam de OAuth Apps criados nos respectivos consoles.

### Google

1. `https://console.cloud.google.com/apis/credentials`
2. Create Credentials → OAuth client ID → Web application
3. Name: `Cloudflare Access — NORA admin`
4. Authorized redirect URI: `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`
5. Copia Client ID + Client Secret
6. No painel Zero Trust → Settings → Authentication → Add new → Google
7. Cola Client ID + Client Secret + clicar Save

### GitHub

1. `https://github.com/settings/developers` → OAuth Apps → New OAuth App
2. Application name: `Cloudflare Access — NORA admin`
3. Homepage URL: `https://admin.nora.systems`
4. Authorization callback URL: `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`
5. Copia Client ID + gera Client Secret
6. No painel Zero Trust → Settings → Authentication → Add new → GitHub
7. Cola Client ID + Client Secret + Save

Depois de adicionar, edita a Access App para permitir os novos IdPs no campo `allowed_idps` (UI: Applications → nora-admin → Edit → Identity providers).

## Operação

### Adicionar OTP IdP manualmente (se o workflow pulou a etapa)

Se o token não tem a permissão opcional, o workflow loga warning e pula a criação do OTP IdP. Adicionar manualmente (30s):

1. `https://one.dash.cloudflare.com/` → seleciona a conta
2. **Settings** → **Authentication**
3. Em **Login methods**, clica **Add new**
4. Escolhe **One-time PIN** → Save

Pronto. Login no `admin.nora.systems` agora pede email e Cloudflare manda código (allowlist do workflow continua valendo).

### Adicionar/remover operador

Re-rodar o workflow com `access_emails` atualizado. Workflow faz upsert da policy.

### Ver logs de acesso

`one.dash.cloudflare.com` → Logs → Access → filtra por hostname `admin.nora.systems`.

Mostra cada tentativa de login (allow/deny), IP de origem, email, IdP usado.

### Suspender acesso temporariamente

Painel Zero Trust → Access → Applications → nora-admin → Edit → desabilita policy ou troca decision para `deny`. Reverte pelo mesmo caminho.

## Solução de problemas

### `Zone status: pending nameserver update`

Nameservers do Namecheap ainda não foram trocados pelos da Cloudflare. Painel Namecheap → Domain List → Manage → Nameservers → Custom DNS → cola os 2 NS dados pela Cloudflare (algo como `xxx.ns.cloudflare.com`). Propagação geralmente em 10-30 min.

### Workflow loga warning em `Resolve team domain`

Geralmente significa que o token escopado não tem `Access: Organizations, IdPs, and Groups Read` — endpoint `/access/organizations` retorna `10000 Authentication error`. Workflow degrada graciosamente: usa `team_name` do input para construir `team_domain`. Steps subsequentes (Access App, Policy) validam funcionalmente.

Se Zero Trust também não estiver habilitado (raro, uma captura de tela do painel já confirmaria), os steps de Access App falhariam com erro claro. Solução: passo 1 da Configuração inicial.

### Login aceita mas página retorna 502/timeout

No modelo ADR 0025 (Tunnel), 502 geralmente significa que o sidecar `cloudflared` não conectou no Cloudflare. Verificar:

```bash
# Réplicas do nora-admin (precisa ≥1 sempre — o sidecar não escala a zero):
az containerapp replica list -n nora-admin-dev -g rg-nora-dev --revision latest -o table

# Logs do sidecar cloudflared (procurar "Registered tunnel connection" e ausência de erro):
az containerapp logs show -n nora-admin-dev -g rg-nora-dev --container cloudflared --tail 100
```

Causas comuns:
- `CLOUDFLARE_TUNNEL_TOKEN` secret faltando/errado → sidecar não conecta. Re-rodar `cloudflare-tunnel.yml`, copiar o connector token do log, atualizar o GitHub Secret, redeploy.
- Container App escalou a zero → trocar `minReplicas` para `≥1` no Bicep do admin.
- Túnel deletado/rotacionado mas DNS ainda aponta para o ID antigo → re-rodar `cloudflare-tunnel.yml` (recria DNS).

### Email não chega (OTP)

Cloudflare manda do remetente `noreply@notify.cloudflare.com`. Verificar spam, e que o email está na allowlist do workflow.

## Revogar token

`dash.cloudflare.com` → My Profile → API Tokens → 3 pontos → Delete. Remover também o secret `CLOUDFLARE_API_TOKEN` do GitHub. Recriar via Configuração inicial passo 2 quando precisar de novo.
