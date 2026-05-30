# Cloudflare Access — proteção do console operador

Runbook pra configurar e operar Cloudflare Access protegendo `admin.nora.systems` (console do nora-admin, ADR 0023). Atualizado em 2026-05-29.

## Por que Cloudflare Access

O console do operador (`nora-admin`) é a porta de entrada do control plane (ADR 0022/0023/0024). Easy Auth via Entra cobre autenticação, mas:

- Depende de App Registration manual + grupo Entra (passo demorado, ver `control-plane-runbook.md`)
- Não dá log central de tentativas de acesso
- Não oferece login com identidades externas (Google/GitHub) sem ADR adicional

Cloudflare Access cobre essas três frentes na borda, **antes** da requisição chegar no Container App:

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

## Setup inicial (uma vez)

### 1. Habilitar Zero Trust na conta CF

Passo manual no dashboard, **não tem API pra criar team**:

1. `https://one.dash.cloudflare.com/`
2. Escolhe a conta de `nora.systems`
3. Onboarding pede team name (vira `<name>.cloudflareaccess.com`) — sugestão: `stratfy`
4. Escolhe plano **Free** (até 50 users, sem expiração)
5. Cartão de crédito é solicitado mas não cobra até passar de 50 users

### 2. Criar API token escopado

`dash.cloudflare.com` → My Profile → API Tokens → Create Custom Token. Permissões mínimas:

- `Account` → `Access: Apps and Policies` → **Edit**
- `Account` → `Access: Service Tokens` → **Edit**
- `Zone` → `DNS` → **Edit**
- `Zone` → `Zone` → **Read**
- Account Resources: conta específica
- Zone Resources: zona específica (`nora.systems`)

Cola o token como secret `CLOUDFLARE_API_TOKEN` no repo (`Settings → Secrets and variables → Actions`).

### 3. Rodar o workflow

`.github/workflows/cloudflare-setup.yml` é `workflow_dispatch` e idempotente. Inputs:

- `admin_hostname` — FQDN do Container App admin (alvo do CNAME). Vazio = pula DNS step, útil quando `enablePlatform=false` ainda
- `team_name` — nome do team (default `stratfy`); precisa coincidir com o team já criado no passo 1
- `access_emails` — CSV de emails autorizados (default já tem os 2 operadores)

Pra descobrir `admin_hostname` quando o admin app subir:

```bash
az containerapp show -n nora-admin-dev -g rg-nora-dev \
  --query properties.configuration.ingress.fqdn -o tsv
```

O workflow faz upsert: criar CNAME, garantir Access App, garantir policy de allowlist, garantir OTP IdP. Re-rodar é seguro.

## Identity providers — adicionar Google e/ou GitHub

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

Depois de adicionar, edita a Access App pra permitir os novos IdPs no campo `allowed_idps` (UI: Applications → nora-admin → Edit → Identity providers).

## Operação

### Adicionar/remover operador

Re-rodar o workflow com `access_emails` atualizado. Workflow faz upsert da policy.

### Ver logs de acesso

`one.dash.cloudflare.com` → Logs → Access → filtra por hostname `admin.nora.systems`.

Mostra cada tentativa de login (allow/deny), IP de origem, email, IdP usado.

### Suspender acesso temporariamente

Painel Zero Trust → Access → Applications → nora-admin → Edit → desabilita policy ou troca decision pra `deny`. Reverte pelo mesmo caminho.

## Troubleshooting

### `Zone status: pending nameserver update`

Nameservers do Namecheap ainda não foram trocados pros da Cloudflare. Painel Namecheap → Domain List → Manage → Nameservers → Custom DNS → cola os 2 NS dados pela Cloudflare (algo tipo `xxx.ns.cloudflare.com`). Propagação geralmente em 10-30 min.

### Workflow falha em `Verify Zero Trust team exists`

Zero Trust não habilitado na conta. Voltar pro passo 1 do Setup Inicial e fazer onboarding no `one.dash.cloudflare.com`.

### Login aceita mas página retorna 502/timeout

CNAME proxied=true tá apontando pra um destino que ainda não existe (Container App não subiu, ou `enablePlatform=false`). Verificar:

```bash
az containerapp show -n nora-admin-dev -g rg-nora-dev --query properties.configuration.ingress.fqdn -o tsv
```

Se vazio: ligar `enablePlatform=true` no `main.dev.bicepparam` e redeploy. Depois re-rodar `cloudflare-setup.yml` com o FQDN correto.

### Email não chega (OTP)

Cloudflare manda do remetente `noreply@notify.cloudflare.com`. Verificar spam, e que o email tá na allowlist do workflow.

## Revogar token

`dash.cloudflare.com` → My Profile → API Tokens → 3 pontos → Delete. Remover também o secret `CLOUDFLARE_API_TOKEN` do GitHub. Recriar via Setup Inicial passo 2 quando precisar de novo.
