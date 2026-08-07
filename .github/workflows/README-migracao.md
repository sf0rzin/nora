# Workflows — o que mudou na migração Azure → Proxmox

> Contexto: [ADR 0034](../../docs/adr/0034-migracao-azure-para-proxmox.md).
> Runbook operacional: [`docs/operations/proxmox-deploy.md`](../../docs/operations/proxmox-deploy.md).
> Desligamento do Azure: [`docs/operations/azure-decommission.md`](../../docs/operations/azure-decommission.md).

A mudança estrutural é uma só, e todo o resto decorre dela:

**O CI deixou de empurrar o deploy. Ele agora só publica imagem e um ponteiro; quem decide o que roda é o host.**

```
ANTES                                    DEPOIS
────────────────────────────────         ────────────────────────────────
GitHub Actions                           GitHub Actions
  azure/login (OIDC federado)              (nenhuma credencial de deploy)
  az containerapp update  ──push──►        push ghcr.io/...:sha-xxxxxxx
  Container Apps                           tag git release/prod/current
                                                        │
                                           Host Proxmox │ (pull, a cada 5 min)
                                             nora-deploy.timer
                                             └─► deploy.sh ─► docker compose up -d --wait
```

## Por que pull, e não push

Não é preferência de estilo — as duas alternativas de push estão fechadas:

- **Runner self-hosted** — o repositório é **público** (ADR 0017) e `deploy-infra.yml` tinha trigger `pull_request`. Um runner persistente na rede doméstica executaria código de PR de fork arbitrário. Risco crítico, não hipotético.
- **SSH a partir do runner GitHub-hosted** — exigiria expor `sshd` à internet, porque runners hospedados não têm faixa de IP estável para allowlist.

O pull elimina os dois: **zero porta inbound, zero chave SSH em Secrets, zero runner.** O host abre conexão de saída para o GHCR e para a Cloudflare, e nada mais.

## Workflow a workflow

| Workflow | Estado | O que mudou |
|---|---|---|
| `ci.yml` | **editado** | O job `infra` deixou de validar Bicep (`az bicep build`) e passou a validar `infra/proxmox/docker-compose.yml` + shellcheck nos scripts. **O nome do job continua `infra`** — é `needs` do `ci-gate`, o único required check da main (ADR 0027); renomear quebraria a branch protection. |
| `build-images.yml` | **editado** | Build e push pro GHCR **intactos** — é a parte que sempre funcionou. Removido o job `deploy-apps` (`azure/login` + `az containerapp update`) e o `permissions: id-token: write` que existia só pro OIDC. No lugar entrou `release-pointer`, que apenas anuncia as tags `sha-<short>` prontas. O fallback do `NEXT_PUBLIC_API_BASE_URL` deixou de apontar pro FQDN do Azure. |
| `deploy-proxmox.yml` | **novo** | Publica o release pointer (tag git `release/prod/<short>` + `release/prod/current`) e, opcionalmente, chama um webhook. Nunca toca o host. |
| `rls-cutover.yml` | **reescrito** | Não conecta mais em banco nenhum. Valida o `R001` num Postgres efêmero e emite o runbook; a execução real virou `infra/proxmox/scripts/rls-cutover.sh`, rodando **no host**. Motivo: o Postgres do Proxmox está na bridge `data` (`internal: true`) publicando só `127.0.0.1:5432` — não existe caminho de rede a partir de um runner. |
| `deploy-infra.yml` | **deletado** | Existia só para `az deployment group create` do Bicep. `infra/bicep/` **permanece no repo** como referência histórica até o decommission terminar. |
| `cloudflare-setup.yml` | editado | Ajustes de hostname: agora o tunnel serve toda a stack, não só o admin. |
| `cloudflare-tunnel.yml` | inalterado | Continua emitindo o connector token. Ganhou importância: agora é o ingress de tudo. |
| `desktop-release.yml` | editado | Ajustes para o STT local (ADR 0035). **Ponto de atenção:** `whisper-rs` compila `whisper.cpp`, que exige toolchain C++ nos três alvos — isso ainda não foi validado num build real. |

## Secrets e Variables do GitHub

### Podem ser DELETADOS depois do decommission

Só apague **depois** de confirmar que o Proxmox está servindo tráfego e o resource group foi removido — ver [`azure-decommission.md`](../../docs/operations/azure-decommission.md).

```
AZURE_CLIENT_ID              AZURE_TENANT_ID           AZURE_SUBSCRIPTION_ID
PG_ADMIN_PASSWORD            PG_PLATFORM_ADMIN_PASSWORD
EASYAUTH_CLIENT_ID           EASYAUTH_CLIENT_SECRET     (já inertes desde o ADR 0025)
NORA_APP_PASSWORD            RLS_TELEMETRY_PASSWORD     (migram pro secrets.env.sops)
```

Também no Entra: a App Registration `sp-nora-github-deploy` e suas **3 federated credentials**
(`github-main-branch`, `github-pull-requests`, `github-environment-dev`).

> O Service Principal tinha **dois** roles, não um: `Contributor` em `rg-nora-dev` **e**
> `Role Based Access Control Administrator` — este segundo porque `modules/keyvault.bicep:67`
> cria role assignments. Remova os dois.

### Continuam necessários

Migram do Key Vault para o `secrets.env.sops`, mas seguem no GitHub enquanto o CI buildar:

```
OPENAI_API_KEY   DEEPSEEK_API_KEY   GEMINI_API_KEY   RESEND_API_KEY
GOOGLE_OAUTH_*   SLACK_OAUTH_*      GH_OAUTH_*       NOTION_OAUTH_*
TODOIST_OAUTH_*  LINEAR_OAUTH_*     MS_OAUTH_*
NORA_TELEGRAM_BOT_TOKEN   TRELLO_API_KEY
NORA_INTEGRATIONS_STATE_SECRET   NORA_INTEGRATIONS_ENC_KEY
NORA_PLATFORM_INTERNAL_TOKEN     NORA_PLATFORM_ADMIN_TOKEN
JWT_SECRET       CLOUDFLARE_TUNNEL_TOKEN
```

### Precisam ser CRIADOS

| Nome | Tipo | Para quê |
|---|---|---|
| `GHCR_PULL_TOKEN` | Secret | PAT com **apenas** `read:packages`, usado pelo host para `docker login ghcr.io`. Não vai no GitHub — vai no `secrets.env.sops` do host. Listado aqui porque é gerado na UI do GitHub. |
| `NORA_RELEASE_WEBHOOK` | Secret (opcional) | URL que o `deploy-proxmox.yml` chama para acordar o agente de pull antes do próximo tick de 5 min. Sem ela o deploy só é mais lento, não quebra. |
| `CF_ACCESS_AUD` | **Secret**, não Variable | Ver abaixo. |

## ⚠️ Bug pré-existente que a migração precisa fechar

`CF_ACCESS_AUD` está cadastrado como **Secret**, mas `deploy-infra.yml:158` e `:239` liam
`${{ vars.CF_ACCESS_AUD }}` — namespace de *Variables*, não de *Secrets*. O valor chegava
**vazio** ao `nora-admin`, e `apps/admin/src/lib/access.ts` faz **fail-open** quando vazio.

Efeito em produção hoje: o Tier 2 do Cloudflare Access está **desligado**, e um JWT do Access
emitido para **outra aplicação da mesma organização Cloudflare** é aceito pelo console de operador.

No novo `docker-compose.yml` isso não pode mais passar despercebido — `CF_ACCESS_AUD` e
`CF_ACCESS_TEAM_DOMAIN` usam a sintaxe `${VAR:?mensagem}`, então o container **se recusa a
subir** sem eles. Preencha os dois antes do primeiro deploy com o profile `platform`.

## Verificação depois de mexer aqui

```bash
gh workflow list --repo sf0rzin/nora
```

Confira que o `ci-gate` continua listando `infra` em `needs` e que a branch protection da
`main` ainda aponta para o check `ci-gate`. Se o required check sumir, PRs passam a mergear
sem CI verde — falha silenciosa e cara.
