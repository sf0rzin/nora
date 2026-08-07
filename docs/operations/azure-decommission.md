---
title: "Runbook — Desligamento da Azure (decommission)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-08-07
---

# Runbook — Desligamento da Azure (decommission)

> **Audiência:** quem executa o corte final da Azure depois da migração para o Proxmox.
>
> **Decisão:** [ADR 0034](../adr/0034-migracao-azure-para-proxmox.md) ·
> **Destino:** [`proxmox-deploy.md`](proxmox-deploy.md) ·
> **Origem (histórico):** [`azure-deploy.md`](azure-deploy.md)
>
> **Pré-requisitos:** Az CLI 2.86+ com login válido, `gh` CLI, acesso ao painel da
> Cloudflare (zona `nora.systems`), e a VM `nora-prod` já provisionada.

> **Este documento é sobre ORDEM, não sobre comandos.** Cada comando aqui é trivial; o que
> não é trivial é a sequência. Executar o passo 5 antes do passo 1 é irreversível e custa
> os dados de produção. **Não pule etapas por pressa** — a pressa é justamente o estado
> mental em que este runbook será lido.

---

## A ordem segura (leia antes de qualquer coisa)

```
  1. RESGATAR os dados          <- primeiro, sempre. Antes de decidir qualquer coisa.
  2. GUARDAR fora do Azure      <- um dump só no laptop não é backup
  3. VALIDAR o Proxmox          <- servindo tráfego real, ainda SEM DNS
  4. APONTAR o DNS              <- o cutover; reversível em minutos
  5. OBSERVAR                   <- período de carência com a Azure ainda de pé
  6. LIMPAR credenciais         <- GitHub Secrets/Variables + Entra
  7. DELETAR o resource group   <- ponto de não-retorno
  8. LIMPAR o repositório       <- Bicep, FQDNs hardcoded, docs
```

**A regra:** nada é deletado enquanto o substituto não estiver provado. A Azure custa
dinheiro parada, mas custa muito menos do que perder `transcripts.raw_text`.

| Passo | Reversível? | Como reverter |
|---|---|---|
| 1-3 | sim | não muda nada em produção |
| 4 (DNS) | **sim, em minutos** | reapontar o CNAME (TTL 1 = auto) |
| 5-6 | parcialmente | credenciais podem ser recriadas; federated credentials, refeitas |
| **7 (delete do RG)** | **NÃO** | após o soft-delete/purge, os dados **não existem mais** |
| 8 | sim | é código versionado |

---

## Passo 0 — Diagnóstico: a assinatura ainda está viva?

Antes de planejar, descubra em que cenário você está. **Isto define quanto tempo você tem.**

```bash
az login          # ou: az login --use-device-code
az account show --query "{name:name, id:id, state:state}" -o table
```

| `state` | Significado | O que fazer |
|---|---|---|
| `Enabled` | assinatura ativa | siga o passo 1 normalmente |
| `Warned` | crédito acabando / aviso de cobrança | **urgente** — resgate hoje |
| `Disabled` / `Expired` / `PastDue` | recursos **suspensos** | ver §[Se a assinatura já estiver desativada](#assinatura-desativada) |

Confirme também se os recursos ainda existem:

```bash
az group show -n rg-nora-dev -o table
az resource list -g rg-nora-dev --query "[].{name:name, type:type}" -o table
az postgres flexible-server list -o table
```

O script de resgate já faz esse pré-voo sozinho e explica o remédio de cada falha:

```bash
infra/proxmox/scripts/rescue-azure-data.sh --check-only
```

<a id="assinatura-desativada"></a>

### Se a assinatura já estiver desativada

Este é o cenário provável (ADR 0034 §Contexto: 522 no domínio público desde ~julho/2026).

**O que acontece com uma assinatura desativada, em ordem:**

1. Os recursos param. O Postgres **não aceita conexão** — não dá para dar `pg_dump`.
2. A assinatura entra num **prazo de retenção**. A Microsoft documenta em torno de
   **30 dias** para assinatura desabilitada por crédito expirado (e 30-90 dias para
   cancelamento voluntário). **Confirme o prazo real no banner do Portal** —
   Subscriptions → a assinatura → Overview: quando há prazo, ele aparece com data.
3. Passado o prazo, os recursos são **excluídos permanentemente**. O PITR de 7 dias do
   Flexible Server vai junto — ele é interno à assinatura.

**Trate o prazo como menor do que o anunciado.** Não existe garantia operacional de aviso.

**Para reativar** (é o que destrava o `pg_dump`):

- Portal → Subscriptions → `Azure for Students` → Overview. Se o crédito expirou/zerou, a
  reativação é via **Upgrade** para Pay-As-You-Go, com cartão. **O crédito estudantil não
  volta** — você passa a pagar pelo que usar.
- Confirme: `az account show --query state -o tsv` deve devolver `Enabled`.
- O Flexible Server pode voltar `Stopped` (ele para sozinho após 7 dias de inatividade, e
  ao suspender a assinatura). Ligue-o:

```bash
az postgres flexible-server start -g rg-nora-dev -n nora-pg-dev-wgl3a3
# ou deixe o script fazer:
infra/proxmox/scripts/rescue-azure-data.sh --start-if-stopped
```

> **O custo de reativar é o preço do resgate, não uma volta atrás na decisão.** Ligar a
> assinatura por alguns dias para extrair um `pg_dump` é barato; é a alternativa 1 do
> ADR 0034, aceita **como caminho de resgate** e rejeitada como destino. Depois do dump
> verificado, desligue.

**Se a reativação não for possível:** rode `--check-only` mesmo assim. Recursos em período
de retenção às vezes ainda respondem ao control plane. Se nada responder, os dados estão
perdidos e o Proxmox sobe **vazio** — nesse caso pule direto para o passo 3, e registre a
perda (é informação de LGPD: os titulares não têm mais dado no sistema).

---

## Passo 1 — Resgatar os dados (PRIMEIRO)

**Esta é a tarefa de maior prioridade da migração inteira.** Acima de qualquer decisão de
arquitetura.

O que está em risco em `nora-pg-dev-wgl3a3`:

- `transcripts.raw_text` — transcrição **bruta**, PII em repouso (ADR 0029);
- as análises (`meeting_analyses` + filhos), Productivity Score (V012), Customer
  Confidence (V017), embeddings (V021);
- os tokens OAuth cifrados das integrações (ADR 0031) — cifrados, mas não recuperáveis sem
  o banco;
- o `flyway_schema_history`, que é o que prova a integridade do restore depois.

**Não existe backup fora do Azure.**

```bash
# A senha vem de $PGPASSWORD, $PG_ADMIN_PASSWORD ou do Key Vault, nessa ordem.
export PGPASSWORD='<senha do nora_admin>'

infra/proxmox/scripts/rescue-azure-data.sh --out-dir /srv/nora/rescue
```

O script abre uma regra de firewall temporária para o seu IP e **a remove no trap EXIT**;
usa cliente Postgres containerizado se o `pg_dump` local for de major version menor que o
servidor (Debian 12 traz client 15, o servidor é 16 — falha clássica na hora errada); e
grava tudo com `umask 077`, porque os dumps contêm PII.

Se não tiver a senha:

```bash
az keyvault secret show --vault-name nora-kv-dev-wgl3a3 \
  --name postgres-password --query value -o tsv
```

O GitHub **não permite ler** um Secret já cadastrado. Se o Key Vault também estiver
inacessível, resete a senha do admin — é seguro, ninguém está usando o banco:

```bash
az postgres flexible-server update -g rg-nora-dev -n nora-pg-dev-wgl3a3 \
  --admin-password '<nova-senha-forte>'
```

### Verificação do dump (obrigatória)

**Um dump que não abre não é backup.** O script já faz isto, mas confira o resultado com
os próprios olhos — é o único momento em que dá para voltar e refazer:

```bash
cd /srv/nora/rescue/<run-id>
cat MANIFEST.txt

# 1) o dump abre? (TOC não pode estar vazio)
pg_restore --list nora.dump | grep -c ' TABLE DATA '

# 2) o checksum bate?
sha256sum -c nora.dump.sha256

# 3) as contagens fazem sentido? (baseline que o restore vai comparar depois)
head -20 nora-counts.tsv
```

Critérios de aceite, todos obrigatórios:

- [ ] `nora.dump` existe, tem **mais de 1 KiB** e `pg_restore --list` devolve TOC não-vazio
- [ ] `nora.dump.sha256` confere
- [ ] `nora-counts.tsv` traz as tabelas que você espera (`tenants`, `meetings`,
      `transcripts`, `meeting_analyses`) com contagem **plausível**, não zero
- [ ] `nora_platform.dump` idem — **ou** a decisão consciente de abrir mão dele

> **Sobre o `nora_platform`:** o control plane (ADR 0022) é largamente reconstruível — o
> catálogo de modelos e as feature flags são recriados pela V001 de `db/platform` no
> primeiro boot. Perde-se a **telemetria histórica de custo** (`usage_events`). O script
> sai com código **3** nesse caso (sucesso parcial). Decida se vale insistir; não trave o
> resgate do banco primário por causa dele.

Exit codes do script: `0` tudo ok · `1` erro de pré-voo · `2` **nada extraído** (Azure
indisponível) · `3` parcial.

---

## Passo 2 — Guardar os dumps fora do Azure (e fora de uma máquina só)

O dump no laptop de quem rodou o script é um ponto único de falha com PII dentro.

- [ ] Copiar `/srv/nora/rescue/<run-id>/` para **pelo menos dois destinos** — um deles
      offline (mídia física) ou em nuvem diferente da Azure.
- [ ] Manter permissão restrita (`chmod 700` no diretório). O script já usa `umask 077`.
- [ ] **Cifrar antes de mover para qualquer destino de terceiro.** O dump é PII bruta de
      titulares reais (ADR 0029). `age -p nora.dump > nora.dump.age` resolve.
- [ ] Registrar **onde** ficou e **quem** tem acesso.
- [ ] **Definir a data de descarte** das cópias de resgate. Elas são uma cópia de dado
      pessoal fora do sistema: quando o Proxmox estiver estável e com backup próprio
      rodando, elas devem ser destruídas (`shred -u`). Sugestão: 90 dias após o go-live.

> Não commite dump nenhum. Nem cifrado. O repositório é **público** (ADR 0017).

---

## Passo 3 — Validar o Proxmox servindo tráfego (ainda SEM DNS)

Só depois do dump verificado. O procedimento completo está em
[`proxmox-deploy.md`](proxmox-deploy.md) — aqui ficam apenas os **portões** que precisam
estar verdes antes de mexer no DNS.

```bash
infra/proxmox/scripts/restore-into-proxmox.sh --from-dir /srv/nora/rescue/<run-id> --sops
```

O script cria os **três** roles antes dos dados, restaura com `--no-owner
--no-privileges`, aplica o `R001__provision_app_roles.sql` **depois** (ele depende do
schema `nora`, que só existe após o DDL entrar) e compara as contagens contra o
`<db>-counts.tsv` do resgate.

Portões de saída (todos obrigatórios):

- [ ] Contagens do restore **batem** com o baseline do resgate (sem `--allow-count-drift`)
- [ ] `flyway_schema_history` na versão esperada, **zero** migrations com `success=false`
- [ ] Os três roles corretos: `nora_app` = `rolbypassrls f`, `nora_telemetry` = `t`
- [ ] Todos os serviços `healthy` em `docker compose -p nora ps`
- [ ] Os quatro hostnames respondem **por Host header**, sem DNS
      (`proxmox-deploy.md` §Verificar)
- [ ] Egress funcionando (OpenAI/Resend alcançáveis a partir do `worker`)
- [ ] Prometheus com métrica da API (**prova de que o javaagent foi trocado**) e Loki
      recebendo log dos containers
- [ ] `CF_ACCESS_AUD` **não vazio** no container `admin`
- [ ] Login real funcionando ponta a ponta

**Se qualquer um falhar, pare.** A Azure ainda está de pé; não há pressa artificial.

---

## Passo 4 — Cutover de DNS

Este é o corte. É reversível em minutos (TTL 1 = automático na Cloudflare), e é o passo
que **resolve o 522**: hoje o DNS resolve para uma origem morta.

### Estado atual (era Azure)

| Nome | Tipo | Conteúdo | Proxy |
|---|---|---|---|
| `nora.systems` (apex) | CNAME (flattening) | FQDN do `nora-web-dev` | proxied |
| `www` | CNAME | FQDN do `nora-web-dev` | proxied |
| `api` | CNAME | FQDN do `nora-api-dev` | proxied |
| `admin` | CNAME | `<tunnel-id-antigo>.cfargotunnel.com` | proxied |
| `asuid`, `asuid.www` | TXT | `customDomainVerificationId` do CAE | n/a |

### Estado alvo (Proxmox)

Todos os hostnames passam a apontar para o **túnel novo** (`nora-prod`), e o roteamento
por Host é do Caddy.

**Forma recomendada:** adicionar os *public hostnames* no túnel (Zero Trust → Networks →
Tunnels → `nora-prod` → Public Hostname). A Cloudflare **cria/atualiza o CNAME proxied
sozinha** para `<tunnel-id>.cfargotunnel.com`.

Ordem sugerida — **do menos crítico para o mais crítico**, validando cada um:

1. `grafana.nora.systems` (novo, sem tráfego) — valida o caminho tunnel → caddy
2. `admin.nora.systems` — **reaponta** do túnel antigo para o novo
3. `api.nora.systems` — valida `/actuator/health` externo antes de seguir
4. `www.nora.systems`
5. `nora.systems` (apex) — por último

Depois de cada um:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health
dig +short api.nora.systems
```

### Limpeza de DNS (só depois de tudo verde)

- [ ] Remover os TXT `asuid` e `asuid.www` — eram verificação de posse do custom domain do
      Container Apps, não têm função no túnel
- [ ] Conferir que **nenhum** registro ainda aponta para `*.azurecontainerapps.io`

> **Cuidado herdado do ADR 0025:** o workflow `cloudflare-setup.yml` é dono da Access
> App/Policy/IdP e **deve rodar sem `admin_hostname`** — com o parâmetro, ele sobrescreve
> o CNAME do túnel e derruba o admin.

### Rollback do cutover

Reapontar o hostname para o FQDN antigo do Container App (com a Azure ainda de pé). Por
isso o passo 7 vem **depois** de um período de observação — a Azure é a sua rede de
segurança durante o passo 5.

---

## Passo 5 — Período de observação (a Azure fica de pé)

**Mínimo sugerido: 7 dias** com a stack nova servindo 100% do tráfego e a Azure ainda
existindo (parada, mas não deletada).

O que observar:

- [ ] Erros no Grafana/Loki (`{container="nora-api"} |= "ERROR"`)
- [ ] O serviço `backup` gerando dump **de hora em hora** em `/srv/nora/backups`
- [ ] Um **restore drill** completo executado com sucesso
      ([`proxmox-deploy.md`](proxmox-deploy.md) §Restore drill) — este é o item que
      converte o RTO de chute em número medido
- [ ] Fluxos que dependem de egress: envio de e-mail (Resend), OAuth das integrações,
      análise LLM
- [ ] Nenhum consumidor reclamando de `speech/token` (o desktop antigo recebe **410 GONE +
      `SPEECH_PROVIDER_GONE`** — sinal terminal, não retry infinito; ver ADR 0035)

**Para reduzir custo durante a observação, sem deletar nada:**

```bash
# Para os Container Apps zerando as réplicas (mantém o recurso e a configuração)
for app in nora-api-dev nora-worker-dev nora-web-dev nora-admin-dev; do
  az containerapp update -g rg-nora-dev -n "$app" --min-replicas 0 --max-replicas 0
done

# Para o Postgres (ele volta com `start`; para sozinho após 7 dias de qualquer forma)
az postgres flexible-server stop -g rg-nora-dev -n nora-pg-dev-wgl3a3
az postgres flexible-server stop -g rg-nora-dev -n nora-pg-platform-dev-wgl3a3
```

> **Parar não é deletar.** Enquanto o RG existir, um novo `pg_dump` ainda é possível (basta
> `start`). É exatamente essa opção que o passo 7 elimina.

---

## Passo 6 — Limpar credenciais

### 6.1 GitHub Secrets

Estado antes da migração: **15 Secrets** e **1 Variable**
(ver [`environment-secrets.md`](environment-secrets.md) §3). O `deploy-infra.yml` não
existe mais — ele foi substituído pelo `deploy-proxmox.yml`, que é **PULL** e não consome
segredo de runtime nenhum.

**Confirme que o valor já está no `secrets.env.sops` ANTES de apagar.** O GitHub não
permite ler um Secret: apagar sem ter copiado é perder o valor.

| Secret | Ação | Motivo |
|---|---|---|
| `AZURE_CLIENT_ID` | **DELETAR** | OIDC do `deploy-infra.yml`, que não existe mais |
| `AZURE_TENANT_ID` | **DELETAR** | idem |
| `AZURE_SUBSCRIPTION_ID` | **DELETAR** | idem |
| `PG_ADMIN_PASSWORD` | migrar → **DELETAR** | vira `POSTGRES_ADMIN_PASSWORD` no SOPS |
| `PG_PLATFORM_ADMIN_PASSWORD` | migrar → **DELETAR** | vira `POSTGRES_PLATFORM_ADMIN_PASSWORD` |
| `JWT_SECRET` | migrar → **DELETAR** | mesmo nome no SOPS. **Trocar invalida todas as sessões** — migre o valor, não gere outro, a menos que queira deslogar todo mundo |
| `OPENAI_API_KEY` | migrar → **DELETAR** | mesmo nome |
| `DEEPSEEK_API_KEY` | migrar → **DELETAR** | mesmo nome |
| `GEMINI_API_KEY` | migrar → **DELETAR** | mesmo nome |
| `RESEND_API_KEY` | migrar → **DELETAR** | mesmo nome |
| `NORA_PLATFORM_INTERNAL_TOKEN` | migrar → **DELETAR** | mesmo nome |
| `NORA_PLATFORM_ADMIN_TOKEN` | migrar → **DELETAR** | mesmo nome |
| `CLOUDFLARE_TUNNEL_TOKEN` | **substituir** → DELETAR | o token do **túnel novo** vai para o SOPS; o antigo morre com o RG |
| `CF_ACCESS_AUD` | **DELETAR** | estava cadastrado **errado** (Secret lido como `vars.` → chegava vazio → fail-OPEN). O AUD é público e agora vive no plano não-secreto do host. **Não recrie como Secret** |
| `CLOUDFLARE_API_TOKEN` | **MANTER** | ainda usado por `cloudflare-setup.yml` / `cloudflare-tunnel.yml` |

Secrets que **passam a existir** (novos, do modelo PULL — ambos opcionais):

| Secret | Uso |
|---|---|
| `NORA_DEPLOY_WEBHOOK_URL` | acorda o agente de pull. Ausente = polling |
| `NORA_DEPLOY_WEBHOOK_TOKEN` | bearer que o agente valida |

Mantidos por outros workflows: `TAURI_SIGNING_PRIVATE_KEY`,
`TAURI_SIGNING_PRIVATE_KEY_PASSWORD` (`desktop-release.yml`), `GITHUB_TOKEN` (automático).

```bash
for s in AZURE_CLIENT_ID AZURE_TENANT_ID AZURE_SUBSCRIPTION_ID \
         PG_ADMIN_PASSWORD PG_PLATFORM_ADMIN_PASSWORD JWT_SECRET \
         OPENAI_API_KEY DEEPSEEK_API_KEY GEMINI_API_KEY RESEND_API_KEY \
         NORA_PLATFORM_INTERNAL_TOKEN NORA_PLATFORM_ADMIN_TOKEN \
         CLOUDFLARE_TUNNEL_TOKEN CF_ACCESS_AUD; do
  gh secret delete "$s" --repo sys0xFF/nora
done
gh secret list --repo sys0xFF/nora
```

### 6.2 GitHub Variables

| Variable | Ação | Motivo |
|---|---|---|
| `NORA_EMAIL_FROM` | **DELETAR** | nenhum workflow lê mais; o valor vive no plano não-secreto do host |
| `NEXT_PUBLIC_API_BASE_URL` | **MANTER e conferir** | build-arg do `build-images.yml`. **Baked em build-time** — se estiver errada, o bundle do `web` chama o endereço errado e nenhuma env de runtime corrige (armadilha 4 do `proxmox-deploy.md`). Deve ser `https://api.nora.systems` |
| `NORA_API_BASE_URL` | **CRIAR se não existir** | usada no bundle do desktop. Hoje **não existe**, e o app cai num fallback hardcoded para o FQDN do Azure — que está morto. Deixar assim entrega um desktop que não conecta |

```bash
gh variable list --repo sys0xFF/nora
gh variable set NORA_API_BASE_URL --body "https://api.nora.systems" --repo sys0xFF/nora
```

### 6.3 Entra ID / App Registrations

**O delete do resource group NÃO apaga App Registration.** Elas vivem no Entra (tenant
`fiap.com.br`), não na assinatura. Os *role assignments*, sim, morrem com o RG.

| Objeto | Ação |
|---|---|
| App Registration `sp-nora-github-deploy` | **DELETAR** (junto com o Service Principal) |
| 3 federated credentials (`ref:refs/heads/main`, `pull_request`, `environment:dev`) | somem com o app; se não puder deletar o app, **remova ao menos estas** |
| Role assignments (`Contributor`, `Role Based Access Control Administrator` em `rg-nora-dev`) | somem com o RG; confira depois |
| App Registration do Easy Auth (ADR 0023) | **verificar se existe.** Provavelmente **nunca foi criada** — o tenant `fiap.com.br` negou `az ad app create` com `Authorization_RequestDenied`, que é o bloqueio que gerou o ADR 0025. `EASYAUTH_CLIENT_ID`/`EASYAUTH_CLIENT_SECRET` eram referências **órfãs** no workflow deletado |

```bash
# Inventário
az ad app list --display-name sp-nora-github-deploy \
  --query "[].{appId:appId, id:id, name:displayName}" -o table
az ad app federated-credential list --id <APP_ID> -o table
az role assignment list --assignee <APP_ID> --all -o table

# Remoção
az ad app delete --id <APP_ID>
```

> **Se o tenant negar o delete** (mesma restrição institucional do ADR 0025): remova as
> federated credentials uma a uma e os role assignments. Sem federated credential e sem
> role, o app fica inerte mesmo que continue listado. Registre a pendência.

### 6.4 Cloudflare

- [ ] **Deletar o túnel antigo** do `nora-admin` (o connector rodava como sidecar no
      Container App e morre com o RG; o registro fica órfão no painel e confunde)
- [ ] **Manter** a Access Application do `admin.nora.systems` e o **mesmo AUD** — o ADR
      0034 reusa (se recriar a App, o AUD muda e o `CF_ACCESS_AUD` precisa ser atualizado)
- [ ] **Criar** a Access Application do `grafana.nora.systems` (rota pública nova)
- [ ] Revisar o `CLOUDFLARE_API_TOKEN`: as permissões continuam corretas para o túnel novo

---

## Passo 7 — Deletar o resource group (PONTO DE NÃO-RETORNO)

**Não execute este passo sem os quatro itens abaixo marcados.**

- [ ] Dump verificado, com checksum conferido, em **dois lugares** fora do Azure (passo 2)
- [ ] Proxmox servindo 100% do tráfego há **pelo menos 7 dias** sem incidente (passo 5)
- [ ] **Restore drill executado com sucesso** a partir do backup do Proxmox — não do dump
      do Azure. Enquanto o drill não passou, a Azure ainda é o seu backup
- [ ] Credenciais migradas e conferidas (passo 6)

```bash
az group delete --name rg-nora-dev --yes --no-wait
```

Demora 5-15 min (o Container Apps Environment é o gargalo).

### Soft-delete: o que sobrevive ao delete do RG

Duas armadilhas herdadas do `azure-deploy.md` (5 e 5b) — **agora a favor**: são a sua
última janela de arrependimento, de **7 dias**.

```bash
az keyvault list-deleted --query "[?starts_with(name, 'nora-')].{name:name}" -o table
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table
```

O Key Vault soft-deleted ainda contém `postgres-password`, `jwt-secret`, etc. Se algum
segredo não foi migrado, **é aqui que você o recupera** — e é por isso que o purge vem
depois, não junto.

**Purge (irreversível, só quando tiver certeza):**

```bash
az keyvault purge --name nora-kv-dev-wgl3a3 --location centralus
az cognitiveservices account purge --location centralus \
  --resource-group rg-nora-dev --name <speech-name>
```

> Se pretende **nunca mais** usar esta assinatura, o purge é opcional — deixar o
> soft-delete expirar sozinho tem o mesmo efeito e mantém a janela de resgate aberta pelo
> prazo inteiro. Purgue só se precisar liberar o **nome global** para recriar algo.

### Depois do RG: a assinatura

- **Se foi feito upgrade para Pay-As-You-Go só para o resgate:** cancele a assinatura
  agora, senão ela continua cobrando (mesmo vazia, há custos residuais).
  Portal → Subscriptions → Cancel subscription.
- **Se a assinatura já estava desativada:** não faça nada. Ela expira sozinha.
- Confirme que não sobrou nada em **outros** resource groups:

```bash
az resource list --query "[?contains(name, 'nora')].{name:name, rg:resourceGroup}" -o table
```

---

## Passo 8 — Limpar o repositório

Depois do RG deletado, o código que referencia Azure vira armadilha para quem chegar
depois: comandos que parecem válidos e apontam para o nada.

### 8.1 O FQDN hardcoded em quatro lugares

`nora-pg-dev-wgl3a3.postgres.database.azure.com` (ADR 0034 §8):

| Arquivo | Ação |
|---|---|
| `infra/bicep/main.dev.bicepparam:140` | sai com o Bicep (§8.2) |
| `.github/workflows/rls-cutover.yml:40` | o workflow inteiro sai — dependia de firewall rule do runner e de OIDC. O flip do RLS passa a ser `psql` local (`proxmox-deploy.md` §Flip do RLS enforce) |
| `docs/operations/rls-cutover-runbook.md:69` | trocar o host por `postgres` e **remover o `?sslmode=require`** (armadilha 1 — derruba o Hikari no boot) |
| `docs/operations/azure-deploy.md:398` | não editar: vira documento histórico (§8.3) |

### 8.2 Infra e workflows

- [ ] `infra/bicep/` — remover. É a referência mais perigosa: descreve uma infra que não
      existe mais e ainda "compila".
- [ ] `.github/workflows/rls-cutover.yml` — remover
- [ ] Conferir que nenhum workflow restante referencia `azure/login`, `id-token: write` ou
      `secrets.AZURE_*`

```bash
grep -rn "azure/login\|AZURE_CLIENT_ID\|azurecontainerapps.io" .github/ infra/ || echo "limpo"
```

### 8.3 Documentação

- [ ] `docs/operations/azure-deploy.md` → marcar como **histórico** no cabeçalho
      (`status: historical`) e apontar para `proxmox-deploy.md`. **Não deletar:** as 8
      armadilhas do Azure for Students são registro de aprendizado, e o ADR 0034 as cita.
- [ ] `docs/engineering/architecture.md:437` — a tabela de recursos Azure (inclui
      `nora-pg-dev-wgl3a3`) precisa virar a tabela da stack Proxmox
- [ ] `docs/operations/production-readiness-gaps.md` — os gaps ancorados em Azure foram
      substituídos parcialmente pelo ADR 0034; reconciliar
- [ ] `docs/operations/environment-secrets.md` — a cartografia inteira assume Key Vault +
      Managed Identity. Reescrever para SOPS+age (o §5.1 do `CF_ACCESS_AUD` fica como
      histórico do bug)

---

## Checklist final

```
RESGATE
  [ ] az account show --query state          -> Enabled (ou reativado)
  [ ] rescue-azure-data.sh                   -> exit 0 (ou 3 consciente)
  [ ] pg_restore --list                      -> TOC não-vazio
  [ ] sha256sum -c                           -> OK
  [ ] cópia em 2 destinos fora do Azure, cifrada
  [ ] data de descarte das cópias definida

PROXMOX
  [ ] restore-into-proxmox.sh                -> contagens batendo
  [ ] flyway_schema_history                  -> versão esperada, 0 falhas
  [ ] 3 roles                                -> nora_app=f, nora_telemetry=t
  [ ] todos os serviços healthy
  [ ] 4 hostnames respondendo por Host header
  [ ] métrica da API no Prometheus (javaagent trocado)
  [ ] CF_ACCESS_AUD não vazio

DNS
  [ ] grafana -> admin -> api -> www -> apex, um a um, verificando
  [ ] TXT asuid/asuid.www removidos
  [ ] nenhum registro para *.azurecontainerapps.io

OBSERVAÇÃO (>= 7 dias)
  [ ] sem erro relevante no Loki
  [ ] backup horário gerando dump
  [ ] RESTORE DRILL executado com sucesso
  [ ] Container Apps zerados / Postgres parado (economia)

CREDENCIAIS
  [ ] 14 Secrets deletados, CLOUDFLARE_API_TOKEN mantido
  [ ] NORA_EMAIL_FROM deletada; NEXT_PUBLIC_API_BASE_URL conferida
  [ ] NORA_API_BASE_URL criada (desktop apontava pro Azure morto)
  [ ] sp-nora-github-deploy deletado (ou federated credentials removidas)
  [ ] túnel antigo deletado; Access App do grafana criada

PONTO DE NÃO-RETORNO
  [ ] az group delete --name rg-nora-dev
  [ ] soft-delete do KV conferido antes do purge
  [ ] assinatura cancelada (se houve upgrade para PAYG)

REPOSITÓRIO
  [ ] infra/bicep removido
  [ ] rls-cutover.yml removido
  [ ] FQDN hardcoded resolvido nos 4 lugares
  [ ] azure-deploy.md marcado como histórico
```

---

## Histórico

| Data | Mudança |
|---|---|
| 2026-08-07 | v1.0 — criado com o ADR 0034. Ordem segura de desligamento: resgate verificado dos dados → validação do Proxmox → cutover de DNS → observação → limpeza de credenciais → delete do RG → limpeza do repo. Inclui o cenário de assinatura já desativada (prazo de retenção) e o inventário de GitHub Secrets/Variables e App Registrations do Entra. |
