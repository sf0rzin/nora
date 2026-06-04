# 0026 — RLS completa, provisionamento de role versionado e cutover do enforce

- Status: parcialmente substituído por 0028 (o design de enforce + a sequência de cutover foram corrigidos no ADR 0028 após achar 3 furos; o V019 e o script R001 deste ADR continuam válidos)
- Data: 2026-06-04
- Decisores: Arquiteto + Stratfy (PO)
- Relacionado: ADR 0002 (multi-tenancy), ADR 0019 (tenant isolation em profundidade: RLS + FK composta), ADR 0024 (telemetria de negócio)

## Contexto

O ADR 0019 entregou RLS Postgres como defesa em profundidade do `tenant_id` filter, mas com **cobertura parcial**: `V016` habilitou `ENABLE ROW LEVEL SECURITY` + `POLICY tenant_isolation` em 12 tabelas e `V017` em +3 (customer confidence). Uma auditoria do schema (2026-06-04) encontrou **~19 tabelas tenant-owned ainda SEM policy**, incluindo:

- **`transcripts`** (V004) — guarda `raw_text` = transcrição bruta = **PII em repouso**;
- filhos da análise: `meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities` (V005);
- `meeting_tags` (V004), `meeting_goals`, `meeting_productivity_assessments` (V012);
- IAM: `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events` (V006);
- tokens de auth: `email_verification_tokens`, `password_reset_tokens` (V003).

No Postgres, uma tabela tenant-owned **sem** `ENABLE ROW LEVEL SECURITY` é totalmente aberta ao role conectado. Ou seja: se o enforce de RLS fosse ligado hoje (role `nora_app` NOBYPASSRLS), as tabelas com policy ficariam isoladas, mas as **sem** policy continuariam legíveis cross-tenant. O pior caso é exatamente `transcripts` — ligar o enforce protegeria `meetings` mas deixaria a PII bruta vazada entre tenants. A cobertura precisa ser completa **antes** de qualquer cutover.

Três acoplamentos adicionais foram identificados:

1. **Role não versionado.** O provisionamento do role `nora_app` (CREATE ROLE + GRANTs + NOBYPASSRLS) existia apenas como **comentário** em `V016` — não era reprodutível nem versionado.
2. **Telemetria fail-closed.** `PrimaryDbBusinessMetricsSource` (telemetria de negócio operador-only, ADR 0024) agrega `meeting_analyses` com `COUNT(*)` / `COUNT(DISTINCT tenant_id)` **sem contexto de tenant** e **fora de `@Transactional`** — logo o `TenantRlsAspect` não seta o GUC `nora.current_tenant_id`. Sob enforce, a policy fail-closed esconderia **todas** as linhas ⇒ o painel mostraria `analyses=0 / tenants=0` **silenciosamente** (sem erro).
3. **Sequência de cutover.** Ligar enforce envolve role + grants + connection string + flag + telemetria, **nessa ordem**. Sem um runbook, é fácil ligar a flag antes do role e derrubar o produto.

## Decisão

### 1. Cobertura completa de RLS (`V019`)

`V019__row_level_security_complete.sql` habilita `ENABLE ROW LEVEL SECURITY` + `CREATE POLICY tenant_isolation` (mesmo predicado e estilo de V016/V017: `tenant_id = nora.current_tenant_id()` com `USING` + `WITH CHECK`) em **15 tabelas** tenant-owned remanescentes que carregam `tenant_id` próprio, com **prioridade para `transcripts`**.

Cobertura após V019: V016 (12) + V017 (3) + V019 (15) = **30 tabelas** com policy direta.

**Fronteiras de cascade (sem policy, por design).** Três tabelas filhas **não** têm `tenant_id` próprio e são acessadas exclusivamente via o pai já isolado (cascade FK `ON DELETE CASCADE`): `iam_invitation_groups` (filha de `iam_user_invitations`), `meeting_goal_expected_outcomes` (filha de `meeting_goals`) e `meeting_outcome_coverage` (filha de `meeting_productivity_assessments`). Seguem a mesma convenção já adotada em V017 para `customer_buying_signals` / `customer_objections`: o isolamento vem do cascade + do fato de que todo acesso passa pelo pai isolado. Documentado explicitamente como fronteira no cabeçalho de V019 — se alguma delas ganhar acesso direto (sem passar pelo pai), precisará de policy via JOIN.

**Tabelas legadas fora de escopo.** `roles` (tem linhas globais `tenant_id IS NULL` — uma policy por tenant as esconderia) e `user_roles` (V002, deprecadas, fora de uso no modelo IAM novo) **não** recebem RLS; saem em migration de limpeza futura.

### 2. Provisionamento de role versionado (`db/operational/R001`)

`services/api/src/main/resources/db/operational/R001__provision_app_roles.sql` — script **idempotente** que:

- cria `nora_app` (LOGIN, **NOBYPASSRLS**) — role de runtime da API;
- cria `nora_telemetry` (LOGIN, **BYPASSRLS**) — leitura operador-only cross-tenant (item 3);
- `GRANT SELECT/INSERT/UPDATE/DELETE` em todas as tabelas do schema `public` + `USAGE/SELECT` em sequences ao `nora_app`; `GRANT EXECUTE` em `nora.current_tenant_id()`;
- `GRANT SELECT` mínimo (least-privilege) em `meeting_analyses` ao `nora_telemetry`;
- `ALTER DEFAULT PRIVILEGES` para que tabelas futuras herdem os grants automaticamente.

**Este script NÃO é uma migration Flyway da aplicação.** Ele cria roles e default privileges — operações que exigem privilégio de **admin** do Postgres (o owner / `postgresAdminLogin`), não o `nora_app`. A API roda Flyway como `nora_app` e não pode (nem deve) executá-lo. Roda manualmente (ou via pipeline de infra com credencial admin), uma vez por ambiente, antes do cutover.

### 3. Telemetria BYPASSRLS-safe

`PrimaryDbBusinessMetricsSource` passa a usar, **quando configurado**, um `JdbcTemplate` dedicado (`telemetryJdbcTemplate`) sobre um pool conectando como `nora_telemetry` (BYPASSRLS). A config é gated por `nora.security.rls.telemetry.url` (`TelemetryDataSourceConfig`, `@ConditionalOnProperty`): **vazia por default** (dev/local/test/CI e prod **antes** do cutover) ⇒ o source cai no `JdbcTemplate` primário, onde o owner bypassa RLS — comportamento atual intacto. Setando as 3 vars (`NORA_TELEMETRY_DATASOURCE_URL/USERNAME/PASSWORD`) no passo de cutover, a agregação cross-tenant continua funcionando sob enforce. Sem isso, sob enforce o painel veria 0 linhas (fail-closed).

Alternativa considerada e rejeitada: função `SECURITY DEFINER` owned por role privilegiada com GRANT ao `nora_app`. Daria o mesmo efeito sem 2º pool, mas acopla a lógica de agregação ao SQL no banco e dificulta evoluir as queries (hoje em Java). O datasource dedicado mantém a query no código e é simétrico ao `PlatformDataSourceConfig` já existente.

### 4. Caminho de Bicep (enforce default OFF)

`main.bicep` ganha os params `rlsEnforce` (bool, **default false**), `rlsTelemetryDatasourceUrl` e `rlsTelemetryPassword` (secure, vai pro KV `rls-telemetry-password`). Quando `rlsEnforce=true`, injeta `NORA_RLS_ENFORCE=true` no `apiApp`; quando a URL de telemetria está setada, injeta o caminho BYPASSRLS dedicado. **O default mantém a produção exatamente como está** — este PR entrega tudo pronto pra ligar, sem ligar. Mudar `DATASOURCE_USERNAME/PASSWORD` para o role `nora_app` é um passo de cutover separado e controlado, **não** feito no template.

### 5. Sequência de cutover (a ordem importa)

1. **Aplicar V019** (deploy normal da API — Flyway cria as policies; enforce ainda OFF, sem efeito porque a API roda como owner).
2. **Provisionar roles**: rodar `R001` como **admin** do Postgres (cria `nora_app` NOBYPASSRLS + `nora_telemetry` BYPASSRLS + grants + default privileges). Popular os secrets no Key Vault (senha do `nora_app`, `rls-telemetry-password`).
3. **Validar a telemetria BYPASSRLS** em staging: setar as 3 vars `NORA_TELEMETRY_DATASOURCE_*` apontando o banco primário com `nora_telemetry`; confirmar que o painel de negócio continua somando (analyses/tenants > 0) **antes** de mexer no enforce.
4. **Ligar enforce em staging**: apontar `DATASOURCE_USERNAME/PASSWORD` para `nora_app` + `NORA_RLS_ENFORCE=true`. Exercitar o smoke cross-tenant (login em 2 tenants, cada um só vê o próprio meeting/transcript/task) e confirmar painel operador ainda somando.
5. **Promover para prod** repetindo (2)–(4). Rollback é trivial: reverter a connection string para o owner e `NORA_RLS_ENFORCE=false` — o schema (policies) fica e não atrapalha.

## Consequências

**Positivas:**

- O furo mais grave (transcrições com PII bruta legíveis cross-tenant sob enforce) é fechado.
- Cobertura de RLS deixa de ser parcial — 30 tabelas com policy + 3 fronteiras de cascade documentadas = 100% das tenant-owned.
- Provisionamento de role versionado e idempotente — reprodutível por ambiente, sem depender de comentário.
- Telemetria operador-only continua funcionando sob enforce, sem virar 0 silencioso.
- Cutover documentado e reversível: enforce é opt-in por flag + role; rollback é só trocar credencial/flag.

**Negativas / trade-offs:**

- Sob enforce, há **dois** roles e (opcionalmente) **dois** pools no caminho da API (`nora_app` + `nora_telemetry`). Mais superfície operacional; mitigado por least-privilege (telemetria só lê `meeting_analyses`).
- `R001` exige privilégio de admin e um passo manual fora do Flyway — risco de esquecer no cutover. Mitigado pelo runbook (item 5) e pela ordem explícita.
- O teste de enforcement (`RlsEnforcementIntegrationTest`) exige Docker/Testcontainers; em dev sem Docker fica não-rodado (CI valida).
- `nora_telemetry` é BYPASSRLS — qualquer uso indevido desse role veria tudo cross-tenant. Mitigado: grant restrito a `SELECT` em `meeting_analyses`, usado só pela telemetria.

## Alternativas Consideradas

1. **Ligar enforce só com a cobertura parcial (V016/V017)** — rejeitado: deixaria `transcripts` (PII bruta) e outras 18 tabelas legíveis cross-tenant — pior do que não ligar.
2. **Policy via JOIN ao pai nas tabelas filhas sem `tenant_id`** — rejeitado para já: custo/complexidade sem ganho real, já que o único caminho de acesso é via o pai isolado. Mantida a convenção de cascade do V017, documentada como fronteira.
3. **Telemetria via `SECURITY DEFINER`** — rejeitado (ver §3): acopla agregação ao SQL no banco; o datasource dedicado mantém a query em Java e é simétrico ao control plane existente.
4. **Enforce sempre-on (sem flag/role)** — rejeitado (herdado do ADR 0019): quebraria dev/Testcontainers que conectam como owner; exige role dedicado antes de valer a pena.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-06-04 | Arquiteto + Stratfy (PO) | ADR criado. V019 (cobertura completa de RLS, prioridade `transcripts`), `db/operational/R001` (provisionamento versionado de `nora_app`/`nora_telemetry`), telemetria BYPASSRLS-safe (`TelemetryDataSourceConfig` + `PrimaryDbBusinessMetricsSource`), params Bicep `rlsEnforce`/telemetria (default OFF) e sequência de cutover. Enforce **não** ligado em prod neste passo. Estende ADR 0019 |
