---
title: "RLS Enforce — Runbook de Cutover (ADR 0028)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# RLS Enforce — Runbook de Cutover (ADR 0028)

Liga o **Row Level Security real** do Postgres como defesa em profundidade do `tenant_id`
(além do filtro app-level, que já é 100% disciplinado). Companheiro operacional do
[ADR 0028](../adr/0028-rls-enforcement-auth-aware.md).

> Atenção: o **flip ao vivo** muda como a API conecta no banco em produção. É reversível
> (um redeploy), mas faça com atenção ao painel. Não pule o smoke.

## Estado da sequência

| Etapa | O quê | Status |
|---|---|---|
| 1 | Mecanismo (V019/V020 + Flyway-admin + onboarding GUC + Bicep switch + teste-gate) | Concluído (PR #197), enforce **default OFF** |
| 2 | Provisionar roles `nora_app` / `nora_telemetry` no Postgres | Pendente — `rls-cutover.yml` |
| 3 | Flip `rlsEnforce=true` no bicepparam → deploy | Pendente — requer "go" do responsável |
| 4 | Smoke ao vivo + monitorar | Pendente |

Rodar a Etapa 2 tem **zero impacto** no app em execução — os roles ficam ociosos até o flip.

## Pré-requisitos (uma vez por ambiente)

Dois GitHub Secrets com senhas fortes (a senha do `nora_app` é a **mesma** usada pelo R001 e
pelo deploy — fonte única de verdade é o secret):

```bash
# Gerar e setar SEM ecoar o valor (hex = alfanumérico, sem dor de quoting):
openssl rand -hex 24 | gh secret set NORA_APP_PASSWORD
openssl rand -hex 24 | gh secret set RLS_TELEMETRY_PASSWORD
gh secret list | grep -E "NORA_APP_PASSWORD|RLS_TELEMETRY_PASSWORD"
```

O `deploy-infra.yml` já injeta esses dois secrets como env vars (o bicepparam os lê via
`readEnvironmentVariable` no flip). Já existem: `AZURE_*`, `PG_ADMIN_PASSWORD`.

## Etapa 2 — Provisionar roles (`rls-cutover.yml`)

```bash
gh workflow run rls-cutover.yml -f confirm=PROVISION
gh run watch $(gh run list --workflow=rls-cutover.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

O workflow (como **admin** `nora_admin`, via OIDC):
1. roda o `db/operational/R001` → cria `nora_app` (NOBYPASSRLS) + `nora_telemetry` (BYPASSRLS)
   + GRANTs + DEFAULT PRIVILEGES;
2. smoke: confere as flags dos roles, que o `nora_app` conecta, lê tabela **exempt** (`users`)
   e que tabela **enforced** (`meetings`) sob tenant aleatório retorna 0 linhas sem erro;
3. fecha a regra de firewall temporária do runner (sempre).

É **idempotente** — pode re-rodar. Senha admin nunca sai do GitHub.

## Etapa 3 — Flip (requer "go" do responsável)

Adicionar ao fim de `infra/bicep/main.dev.bicepparam`:

```bicep
// ---- RLS enforce (ADR 0028) — flip do cutover ----
// nora_app (NOBYPASSRLS) + nora_telemetry (BYPASSRLS) provisionados pelo rls-cutover.yml.
param rlsEnforce = true
param appDbPassword = readEnvironmentVariable('NORA_APP_PASSWORD')
param rlsTelemetryDatasourceUrl = 'jdbc:postgresql://nora-pg-dev-wgl3a3.postgres.database.azure.com:5432/nora?sslmode=require'
param rlsTelemetryPassword = readEnvironmentVariable('RLS_TELEMETRY_PASSWORD')
```

O que o flip liga no Container App `nora-api-dev` (via `main.bicep`):
- `DATASOURCE_USERNAME=nora_app` + `DATASOURCE_PASSWORD`←KV `nora-app-password` (NOBYPASSRLS → RLS vale);
- Flyway separado como **admin**: `SPRING_FLYWAY_USER=nora_admin` + senha←KV `postgres-password`
  (DDL + dono das tabelas);
- `NORA_RLS_ENFORCE=true` (liga o `TenantRlsAspect`);
- caminho BYPASSRLS da telemetria: `NORA_TELEMETRY_DATASOURCE_*` como `nora_telemetry`
  (painel operador segue agregando cross-tenant).

PR com **só** essa mudança → merge → `deploy-infra.yml` aplica. A API reinicia conectando
como `nora_app`.

> Não inclua o flip no mesmo PR do provisionamento: mergear o flip **antes** da Etapa 2 sobe
> a API apontando para um `nora_app` que não existe → boot quebra.

## Etapa 4 — Smoke ao vivo

- **Auth (tabelas exempt):** signup → verificação de email → login → aceite de convite → reset de senha.
- **Tabelas enforced:** upload de transcript → list/detail aparecem → análise async completa (COMPLETED).
- **Isolamento:** tenant B **não** vê meeting/transcript do tenant A.
- **Operador:** painel admin ainda agrega métricas (telemetria BYPASSRLS).

## Rollback (trivial, reversível)

Remover as 4 linhas do flip do bicepparam (ou `param rlsEnforce = false`) → merge → redeploy.
A API volta a conectar como `nora_admin` (bypassa RLS). O schema (V019/V020) e os roles ficam —
sem efeito enquanto enforce está OFF.

## Notas operacionais

- **`ServerIsBusy` no `azure.extensions`:** o `deploy-infra.yml` reescreve o parâmetro
  `azure.extensions` (já em `PGCRYPTO,CITEXT`) a cada deploy; em servidor B1ms ocupado dá
  `ServerIsBusy` (no-op transiente). Remédio: confirmar `state=Ready` nos dois Postgres
  (`az postgres flexible-server show ... --query state`) e re-rodar **uma** vez (não repetir em
  excesso — cada tentativa reinicia o server e ocupa a próxima).
- **Por que dois roles:** `nora_app` é NOBYPASSRLS (RLS vale para os dados de tenant); `nora_telemetry`
  é BYPASSRLS (leitura agregada operador-only, cross-tenant intencional). Ver ADR 0028 §telemetria.
- **Escopo auth-aware:** identidade (users/tenants/tokens/invitations) e IAM authz (groups/policies/…)
  têm RLS **desabilitada** (V020) — auth é cross-tenant por design. Negócio/PII fica enforced.
