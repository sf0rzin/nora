---
title: "Runbook — Desligamento da Azure (decommission)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 2.0
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
> não é trivial é a sequência. O passo 5 é irreversível — mas, neste projeto, o que ele
> destrói é infraestrutura substituível, não dado insubstituível. Ver abaixo.

---

## O que este runbook NÃO precisa fazer

**Não há dado a resgatar.** O NORA é um projeto educacional (FIAP Challenge 2026 × TOTVS):
o `rg-nora-dev` serve um domínio real e foi construído com padrões de produção, mas **não
há dado de produção nem base de usuários**, e por decisão do PO não haverá — o produto não
vai operar comercialmente nesta encarnação. O conteúdo dos dois Postgres é material de
demonstração, reproduzível.

Isso elimina a parte mais cara e mais tensa de um decommission. Concretamente:

- **Sem `pg_dump` no caminho crítico.** O banco no Proxmox nasce **vazio**: o Flyway cria o
  schema do zero e os roles do RLS saem do `postgres/init/01-roles-and-db.sql`.
- **Sem relógio de retenção.** Se a assinatura já expirou e a Azure já apagou tudo, não se
  perdeu nada. Não há urgência a gerenciar.
- **Sem cópias de PII circulando.** Não existe dump a cifrar, guardar em dois lugares,
  registrar quem tem acesso e destruir em 90 dias.
- **Sem reativar a assinatura.** Não há motivo para pagar Pay-As-You-Go só para conseguir
  extrair alguma coisa.

> Se algum dia o NORA virar produto com titulares reais, **este runbook não serve mais**:
> `transcripts.raw_text` guarda PII em repouso (ADR 0029) e os tokens OAuth (ADR 0031) não
> são recuperáveis sem o banco. Nesse cenário, dump verificado antes de tudo volta a ser o
> passo 1. A versão 1.0 deste documento, no histórico do git, tem esse procedimento.

---

## A ordem segura

```
  1. VALIDAR o Proxmox          <- servindo tráfego real, ainda SEM DNS
  2. APONTAR o DNS              <- o cutover; reversível em minutos
  3. OBSERVAR                   <- período de carência (só se a Azure ainda estiver de pé)
  4. LIMPAR credenciais         <- GitHub Secrets/Variables + Entra
  5. DELETAR o resource group   <- ponto de não-retorno
  6. LIMPAR o repositório       <- Bicep, FQDNs hardcoded, docs
```

**A regra que sobra:** nada é deletado enquanto o substituto não estiver provado. Não pelo
dado — pela capacidade de comparar comportamento entre o antigo e o novo quando algo sair
diferente do esperado.

| Passo | Reversível? | Como reverter |
|---|---|---|
| 1 | sim | não muda nada em produção |
| 2 (DNS) | **sim, em minutos** | reapontar o CNAME (TTL 1 = auto) |
| 3-4 | parcialmente | credenciais podem ser recriadas; federated credentials, refeitas |
| **5 (delete do RG)** | **NÃO** | mas o que se perde é infraestrutura declarada em `infra/bicep/`, recriável |
| 6 | sim | é código versionado |

---

## Passo 0 — Diagnóstico: em que cenário você está

Isto não define mais *quanto tempo você tem* — define apenas **quanto trabalho o
decommission ainda dá**.

```bash
az account show --query "{nome:name, estado:state, id:id}" -o table
```

| `state` | O que fazer |
|---|---|
| `Enabled` | Siga o runbook inteiro. O RG existe e precisa ser deletado. |
| `Disabled` / `Warned` | Nada urgente. Você pode reativar só para deletar o RG e parar qualquer cobrança residual, **ou simplesmente deixar expirar** — a Azure remove os recursos sozinha ao fim da retenção. Vá direto ao passo 4 (limpar credenciais) e ao 6 (limpar o repositório). |
| `PastDue` | Há fatura em aberto. Resolva no portal antes, senão o `az group delete` falha. |
| erro de login | A assinatura pode já ter sido removida. Confirme no portal; se sumiu, o decommission de infra está feito — restam os passos 4 e 6. |

Confirme também se o ambiente ainda responde:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' \
  https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io/actuator/health
```

Em 2026-08-07 os dois deram erro de conexão — origem fora do ar, não problema de
Cloudflare. Se continuar assim, **pule o passo 3** (período de observação com a Azure de
pé): não há nada de pé para observar, e não há rollback para a Azure.

---

## Passo 1 — Validar o Proxmox servindo tráfego (ainda SEM DNS)

O procedimento completo está em [`proxmox-deploy.md`](proxmox-deploy.md) — aqui ficam
apenas os **portões** que precisam estar verdes antes de mexer no DNS.

O banco nasce **vazio**. Não há restore de dados: o Flyway aplica as 26 migrations do zero
no primeiro boot da API, e os três roles do RLS saem do
`infra/proxmox/postgres/init/01-roles-and-db.sql`, que o initdb executa.

> O `restore-into-proxmox.sh` existe para o caminho de **recuperação a partir de um backup
> do próprio Proxmox** (os dumps que o serviço `backup` gera), não para trazer nada da
> Azure. Não é usado neste passo.

Portões de saída (todos obrigatórios):

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

**Se qualquer um falhar, pare.** Nada aqui tem prazo: a Azure já está fora do ar, então não há
nem serviço degradando nem cobrança correndo enquanto você investiga.

---

## Passo 2 — Cutover de DNS

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
isso o passo 5 vem **depois** de um período de observação — a Azure é a sua rede de
segurança durante o passo 3.

---

## Passo 3 — Período de observação (a Azure fica de pé)

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
> `start`). É exatamente essa opção que o passo 5 elimina.

---

## Passo 4 — Limpar credenciais

### 4.1 GitHub Secrets

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

### 4.2 GitHub Variables

| Variable | Ação | Motivo |
|---|---|---|
| `NORA_EMAIL_FROM` | **DELETAR** | nenhum workflow lê mais; o valor vive no plano não-secreto do host |
| `NEXT_PUBLIC_API_BASE_URL` | **MANTER e conferir** | build-arg do `build-images.yml`. **Baked em build-time** — se estiver errada, o bundle do `web` chama o endereço errado e nenhuma env de runtime corrige (armadilha 4 do `proxmox-deploy.md`). Deve ser `https://api.nora.systems` |
| `NORA_API_BASE_URL` | **CRIAR se não existir** | usada no bundle do desktop. Hoje **não existe**, e o app cai num fallback hardcoded para o FQDN do Azure — que está morto. Deixar assim entrega um desktop que não conecta |

```bash
gh variable list --repo sys0xFF/nora
gh variable set NORA_API_BASE_URL --body "https://api.nora.systems" --repo sys0xFF/nora
```

### 4.3 Entra ID / App Registrations

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

### 4.4 Cloudflare

- [ ] **Deletar o túnel antigo** do `nora-admin` (o connector rodava como sidecar no
      Container App e morre com o RG; o registro fica órfão no painel e confunde)
- [ ] **Manter** a Access Application do `admin.nora.systems` e o **mesmo AUD** — o ADR
      0034 reusa (se recriar a App, o AUD muda e o `CF_ACCESS_AUD` precisa ser atualizado)
- [ ] **Criar** a Access Application do `grafana.nora.systems` (rota pública nova)
- [ ] Revisar o `CLOUDFLARE_API_TOKEN`: as permissões continuam corretas para o túnel novo

---

## Passo 5 — Deletar o resource group (PONTO DE NÃO-RETORNO)

O que se perde aqui é **infraestrutura declarada em `infra/bicep/`** — recriável a partir
do repositório — e o conteúdo descartável dos dois bancos. Não há dado insubstituível em
jogo (ver §"O que este runbook NÃO precisa fazer"). Ainda assim, marque os itens: o valor
da Azure de pé neste ponto não é backup, é **poder comparar comportamento** quando o
Proxmox se comportar diferente do esperado.

- [ ] Proxmox validado servindo tráfego real por hostname de teste (passo 1)
- [ ] Proxmox servindo 100% do tráfego sem incidente (passo 3) — 7 dias é o ideal; para uma
      demo acadêmica com data marcada, o critério real é *não fazer isto na véspera do pitch*
- [ ] **Restore drill executado com sucesso** a partir do backup do Proxmox
      (`scripts/restore-drill.sh`). Não porque a Azure seja rede de segurança — ela não é,
      já está fora do ar — mas porque um restore nunca testado é um procedimento que não
      existe, e o `production-readiness-gaps.md:67` já admitia esse gap
- [ ] Credenciais migradas e conferidas (passo 4)

> Se a assinatura já estiver desativada e os recursos já removidos, este passo é no-op.
> Confirme com `az group show --name rg-nora-dev` e siga para o passo 6.

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

- **Se foi feito upgrade para Pay-As-You-Go em algum momento:** cancele a assinatura
  agora, senão ela continua cobrando (mesmo vazia, há custos residuais).
  Portal → Subscriptions → Cancel subscription.
- **Se a assinatura já estava desativada:** não faça nada. Ela expira sozinha.
- Confirme que não sobrou nada em **outros** resource groups:

```bash
az resource list --query "[?contains(name, 'nora')].{name:name, rg:resourceGroup}" -o table
```

---

## Passo 6 — Limpar o repositório

Depois do RG deletado, o código que referencia Azure vira armadilha para quem chegar
depois: comandos que parecem válidos e apontam para o nada.

### 6.1 O FQDN hardcoded em quatro lugares

`nora-pg-dev-wgl3a3.postgres.database.azure.com` (ADR 0034):

| Arquivo | Ação |
|---|---|
| `infra/bicep/main.dev.bicepparam:140` | sai com o Bicep (§6.2) |
| `.github/workflows/rls-cutover.yml:40` | o workflow inteiro sai — dependia de firewall rule do runner e de OIDC. O flip do RLS passa a ser `psql` local (`proxmox-deploy.md` §Flip do RLS enforce) |
| `docs/operations/rls-cutover-runbook.md:69` | trocar o host por `postgres` e **remover o `?sslmode=require`** (armadilha 1 — derruba o Hikari no boot) |
| `docs/operations/azure-deploy.md:398` | não editar: vira documento histórico (§6.3) |

### 6.2 Infra e workflows

- [ ] `infra/bicep/` — remover. É a referência mais perigosa: descreve uma infra que não
      existe mais e ainda "compila".
- [ ] `.github/workflows/rls-cutover.yml` — remover
- [ ] Conferir que nenhum workflow restante referencia `azure/login`, `id-token: write` ou
      `secrets.AZURE_*`

```bash
grep -rn "azure/login\|AZURE_CLIENT_ID\|azurecontainerapps.io" .github/ infra/ || echo "limpo"
```

### 6.3 Documentação

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
DIAGNÓSTICO  (sem resgate: não há dado a preservar — ver §"O que este runbook NÃO precisa fazer")
  [ ] az account show --query state          -> anotado; define quanto trabalho resta

PROXMOX
  [ ] stack sobe com banco VAZIO             -> Flyway cria o schema do zero
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

OBSERVAÇÃO (pular se a Azure já estiver fora do ar — não há o que observar)
  [ ] sem erro relevante no Loki
  [ ] backup horário gerando dump
  [ ] RESTORE DRILL executado com sucesso   <- fecha o gap do production-readiness-gaps.md:67
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
| 2026-08-07 | v1.0 — criado com o ADR 0034. Ordem segura de desligamento em 8 passos, começando por resgate verificado dos dados. |
| 2026-08-07 | v2.0 — correção de premissa. O PO esclareceu que o NORA é educacional, sem dado de produção nem base de usuários, e que não operará comercialmente. Removidos os passos de resgate e de guarda de dumps (e o `rescue-azure-data.sh`); o runbook cai de 8 para 6 passos e o banco no Proxmox passa a nascer vazio. O procedimento de resgate, se algum dia voltar a ser necessário, está na v1.0 no histórico do git. |
