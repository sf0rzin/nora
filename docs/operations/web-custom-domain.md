---
title: "Runbook — domínio customizado do web (nora.systems)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-08
relacionado: "ADR 0025 (admin via Cloudflare Tunnel — superfície distinta); docs/operations/cloudflare-access.md"
---

# Runbook — domínio customizado do web (`nora.systems`)

> Liga o domínio raiz `nora.systems` e o `www.nora.systems` ao app web (`apps/web`),
> servido pelo Azure Container App `nora-web-dev`, com a Cloudflare na frente (proxied:
> WAF/DDoS + IP de origem escondido) e certificado TLS gerenciado pelo Azure.

Diferente do `admin.nora.systems` (que não tem ingress público e entra por **Cloudflare
Tunnel**, ADR 0025), o web **tem ingress público** no Azure. Por isso o caminho é mais
simples: DNS proxied apontando para o app + custom domain com certificado gerenciado.

## Resultado

- `https://nora.systems` → app web (HTTP 200, TLS válido), via Cloudflare
- `https://www.nora.systems` → idem
- `admin.nora.systems` permanece intacto (tunnel próprio)

## Pré-requisitos

- **Azure CLI** logado com papel `Contributor` em `rg-nora-dev`.
- **Acesso de DNS na Cloudflare** (zona `nora.systems`): via cofre `cf-api` (skill
  `sforzin-setup`, token DNS:Edit) ou token equivalente / dashboard.
- `jq` para formatar as respostas da API.

## Valores de referência deste ambiente

| Item | Valor | Como obter |
|---|---|---|
| Resource group | `rg-nora-dev` | — |
| Container App (web) | `nora-web-dev` | `az containerapp list -g rg-nora-dev -o table` |
| Managed environment | `nora-cae-dev` | `az containerapp env list -g rg-nora-dev -o table` |
| FQDN padrão do app | `nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` | `az containerapp show -g rg-nora-dev -n nora-web-dev --query properties.configuration.ingress.fqdn -o tsv` |
| IP estático do env | `20.236.215.95` | `az containerapp env show -g rg-nora-dev -n nora-cae-dev --query properties.staticIp -o tsv` |
| `customDomainVerificationId` (valor do `asuid`) | `D07F1728…A1DF6` | `az containerapp env show -g rg-nora-dev -n nora-cae-dev --query properties.customDomainConfiguration.customDomainVerificationId -o tsv` |
| Cloudflare zone id | `02a6a502…b8500` | `cf-api 'https://api.cloudflare.com/client/v4/zones?name=nora.systems' \| jq -r '.result[0].id'` |

## Sequência (estado)

| Passo | Ação | Estado de proxy | Status |
|---|---|---|---|
| 1 | `asuid` TXT (apex + www) | n/a (TXT) | Concluído |
| 2 | apex/www → Azure (CNAME) | DNS-only durante provisioning | Concluído |
| 3 | Azure: `hostname add` + `bind` (cert) | — | Concluído |
| 4 | Verificar direto (DNS-only) | DNS-only | Concluído |
| 5 | Flip para proxied (flattening) | proxied | Concluído |
| 6 | Verificar via Cloudflare | proxied | Concluído |

## Passos

> As chamadas `cf-api` rodam server-side via o cofre (a chave nunca sai da VM). Os
> registros são idempotentes: re-rodar reconcilia. `$ZONE` = zone id; `$VERID` =
> `customDomainVerificationId`; `$FQDN` = FQDN padrão do app.

### 1. Verificação de posse do domínio (`asuid` TXT)

Crie um TXT `asuid` (apex) e `asuid.www` com o `customDomainVerificationId` do env:

```bash
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"TXT\",\"name\":\"asuid\",\"content\":\"$VERID\",\"ttl\":1}"
# repetir com "name":"asuid.www"
```

### 2. Apontar apex e www para o Azure (DNS-only durante o provisioning)

O apex usa **CNAME flattening** (CNAME na raiz → FQDN do Azure; a Cloudflare resolve como
A na borda). Mantenha **DNS-only (cinza)** até o certificado ser emitido — o Azure precisa
enxergar o domínio apontando para ele na validação.

```bash
# apex: substituir o registro existente da raiz por CNAME -> FQDN, proxied=false
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$APEX_ID" -X PUT \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"CNAME\",\"name\":\"@\",\"content\":\"$FQDN\",\"proxied\":false,\"ttl\":1}"
# www: CNAME -> FQDN, proxied=false  (substitui o parking da Namecheap, se houver)
```

### 3. Azure — adicionar o hostname e emitir o certificado gerenciado

Use **validação HTTP** (`--validation-method HTTP`). A validação por TXT do `az
containerapp` é semi-manual (imprime um token e espera um TXT adicional numa segunda fase)
e falha quando executada de uma vez; a HTTP é automática — o Azure serve o desafio no IP
que o DNS já aponta.

```bash
az containerapp hostname add  -g rg-nora-dev -n nora-web-dev --hostname nora.systems
az containerapp hostname bind -g rg-nora-dev -n nora-web-dev --hostname nora.systems \
  -e nora-cae-dev --validation-method HTTP
# repetir para www.nora.systems
```

O resultado esperado por hostname é `bindingType: SniEnabled` com um `certificateId`. A
emissão leva de 1 a alguns minutos.

> Se um certificado ficar preso em `Pending` (ex.: tentativa anterior por TXT), apague-o
> antes de refazer:
> `az containerapp env certificate list -g rg-nora-dev -n nora-cae-dev --managed-certificates-only`
> e `az containerapp env certificate delete -g rg-nora-dev -n nora-cae-dev --certificate <nome> --yes`.

### 4. Verificar com a origem direta (ainda DNS-only)

```bash
curl -sS -o /dev/null -w "http=%{http_code} ssl=%{ssl_verify_result}\n" https://nora.systems
# Esperado: http=200 ssl=0 (cert válido). Se houver atraso de propagação, repita.
```

### 5. Flip para proxied (Cloudflare na frente)

Com o certificado emitido, ligue o proxy (laranja). O apex fica CNAME→FQDN proxied
(flattening); o `www` idem.

```bash
cf-api "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$APEX_ID" -X PUT \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"CNAME\",\"name\":\"@\",\"content\":\"$FQDN\",\"proxied\":true,\"ttl\":1}"
# www: idem com proxied=true
```

O modo SSL/TLS da zona deve ser **Full** (ou Full strict). Com Full, a Cloudflare conecta
na origem por HTTPS e aceita o certificado gerenciado do Azure (que é público e válido).

### 6. Verificar via Cloudflare

```bash
curl -sS -I https://nora.systems | grep -iE "^(server|cf-ray):"
# Esperado: Server: cloudflare + CF-RAY presente, e HTTP 200 no corpo.
```

## Smoke final

- `https://nora.systems` e `https://www.nora.systems` retornam 200 servidos pelo app
  (header `x-powered-by: Next.js`), via Cloudflare.
- `https://admin.nora.systems` continua respondendo (302 → login do Cloudflare Access).

## Rollback

- **Reverter DNS:** apontar apex/www de volta ao destino anterior (ou `proxied:false`)
  via `cf-api ... -X PUT`.
- **Remover o custom domain do Azure:**
  `az containerapp hostname delete -g rg-nora-dev -n nora-web-dev --hostname nora.systems --yes`
  (idem www). O FQDN padrão do app continua funcionando.

## Atenção — melhorias recomendadas (débito conhecido)

1. **Renovação do certificado atrás do proxy.** O certificado gerenciado do Azure renova
   por validação HTTP; com o domínio proxied, a opção "Always Use HTTPS" da Cloudflare pode
   interceptar o desafio HTTP-01 e **quebrar a renovação automática**. O certificado atual
   vale ~6 meses. **Fix durável:** instalar um **Cloudflare Origin Certificate** (validade
   de 15 anos) no Azure como certificado próprio e usar SSL "Full (strict)" — elimina a
   dependência da renovação do Azure. Exige um token Cloudflare com `SSL:Edit` (o do cofre
   é apenas `DNS:Edit`).
2. **SSL/TLS da zona = Full → Full (strict).** O certificado do Azure é público e válido,
   então o modo estrito é seguro e recomendado. Exige `Zone Settings:Edit`.
3. **Redirect canônico `www → apex`.** Hoje ambos servem o app (conteúdo duplicado, sessão
   por host). Definir o apex como canônico e redirecionar `www` evita cookie/sessão
   divididos. Exige `Page Rules` ou `Rulesets` (Dynamic Redirects) no token.

## Notas

- A automação de DNS usa o cofre `cf-api` (skill `sforzin-setup`); a chave Cloudflare nunca
  sai da VM. Alternativa: dashboard da Cloudflare.
- `admin.nora.systems` é uma superfície separada (Cloudflare Tunnel + Access, ADR 0025) e
  não é afetada por este runbook.

## Histórico do documento

| Versão | Data | Autor | Mudança |
|---|---|---|---|
| 1.0 | 2026-06-08 | Arquiteto NORA (Tech Lead) | Criação após ligar `nora.systems`/`www` ao web |
