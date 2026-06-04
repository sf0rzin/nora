# 0028 — RLS enforcement auth-aware: escopo por dado, Flyway-as-admin e cutover

- Status: aceito
- Data: 2026-06-04
- Decisores: Arquiteto + Stratfy (PO)
- Relacionado: substitui a parte de **design do enforce + cutover** do ADR 0026 (mantém o V019 e o role provisioning); estende ADR 0002 / 0019; relacionado a ADR 0024 (telemetria)

## Contexto

O ADR 0026 (proposto) entregou o V019 (policies de RLS em todas as ~30 tabelas tenant-owned, fechando o gap crítico de `transcripts`) + o script de roles `R001` + o caminho de telemetria BYPASSRLS. Mas, ao preparar o cutover de fato, uma investigação do código (2026-06-04) achou que **o design de enforce do 0026 estava incompleto e quebraria o app**. Três problemas:

1. **Flyway-DDL.** O 0026 manda a API conectar como `nora_app` (NOBYPASSRLS), e o Flyway roda no boot da API. Mas `nora_app` só tem `USAGE` no schema (sem `CREATE`/`ALTER`) — o próximo deploy com uma migration nova **quebra a API no boot**. (As tabelas usam `ENABLE`, não `FORCE`, RLS — então o owner bypassa; quem cria a tabela é o owner.)
2. **Switch de conexão ausente.** O `main.bicep` não tem como apontar o `DATASOURCE` da API pro `nora_app` (o próprio template admite). Ligar `rlsEnforce=true` sozinho é **no-op** (o aspect seta o GUC, mas a conexão admin bypassa).
3. **🔴 Auth quebra sob enforce (o 0026 não viu).** `AuthService.login`/`signup` usam `UserRepository.findByEmail` — busca **global, cross-tenant** (achar o user por email *antes* de saber o tenant). O `TenantRlsAspect` só seta o GUC quando há tenant autenticado (`if tenantId != null`). Sob enforce com `nora_app`, requests não-autenticados (login, signup, aceite de convite, verificação de email, reset de senha) ficam **sem GUC → fail-closed → auth inteira quebra**.

RLS só vale para roles **não-owner** e **NOBYPASSRLS**. O app precisa ser esse role para os dados de tenant, mas precisa de acesso cross-tenant para auth. Num único papel de conexão, isso conflita.

## Decisão

### 1. Escopo do enforce: por sensibilidade do dado, não "todas as tabelas"

RLS enforce vale para as tabelas de **dados de negócio + PII + autorização IAM** — onde está o valor da defesa em profundidade:

- `transcripts` (PII bruta — prioridade), `meetings`, `meeting_analyses` + filhos (`meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities`, `meeting_participants`), `meeting_tags`, `meeting_goals`, `meeting_productivity_assessments`, `meeting_account_links`;
- `customer_accounts`, `customer_confidence_assessments`;
- `tenant_contexts` (contexto company/product do tenant).

As tabelas de **IDENTIDADE** e de **AUTORIZAÇÃO IAM** ficam com **escopo de aplicação** (RLS desabilitada via `V020`):

- **Identidade** — auth é cross-tenant por natureza (lida/escrita sem contexto de tenant): `users` (login por email é global), `tenants`, `email_verification_tokens`, `password_reset_tokens`, `refresh_tokens`, `iam_user_invitations`.
- **Autorização IAM** — config de autorização (não PII de cliente), escrita por fluxos de onboarding **sem JWT** (aceite de convite anexa o user a grupos → `iam_user_groups`; signup grava audit → `iam_audit_events`): `iam_groups`, `iam_policies`, `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events`. Exemptar elimina fiação de GUC nesses fluxos; o isolamento segue pelo `PolicyEvaluator` + queries tenant-scoped (disciplinado).

> Isto **revisa** o "100% das tabelas" do ADR 0026 para **"100% dos dados de negócio/PII; identidade com escopo de aplicação"** — a postura padrão de mercado para SaaS multi-tenant com signup self-service. O filtro `tenant_id` aplicacional nas tabelas de identidade já é 100% disciplinado (auditoria 2026-06-03), e o gap crítico (PII de `transcripts`) é fechado de verdade.

### 2. Flyway-as-admin, runtime-as-nora_app

A API runtime conecta como `nora_app` (NOBYPASSRLS → RLS vale), mas o **Flyway usa credenciais separadas de admin** (Spring Boot suporta `spring.flyway.{url,user,password}` independente do `spring.datasource`). O owner (admin) cria/altera o schema e **é dono das tabelas** (inclusive futuras) → `nora_app` (não-owner) é sujeito à RLS. Resolve o Flyway-DDL **e** a semântica de owner-bypass de uma vez. Default (sem as vars de Flyway setadas): Flyway = datasource (dev/test/pré-cutover intactos).

### 3. Pipeline de análise seta o tenant context (a única escrita enforced fora de request-com-JWT)

Com identidade + IAM exemptas, o **único** caminho que escreve em tabela enforced sem um JWT na thread é o **pipeline de análise**: `AnalysisService.run` (e a análise live) roda **async** num thread de executor — o `TenantContextHolder` (ThreadLocal) não é propagado, então o `TenantRlsAspect` não seta o GUC. Como o pipeline recebe o `tenantId`, ele chama o helper `TenantRlsContext.runWithTenant(tenantId, ...)` (executa `set_config('nora.current_tenant_id', ?, true)` na transação corrente) antes de ler o `transcript` e escrever `meeting_analyses` + filhos. Todas as demais tabelas enforced são tocadas só por requests autenticados (GUC setado pelo aspect). Lookups genuinamente cross-tenant (login por email, convite/token por hash) batem só em tabelas exemptas → funcionam sem GUC.

### 4. Bicep: switch de conexão + Flyway admin (enforce default OFF)

`main.bicep` ganha o param `appDbUsername` (default `nora_admin`) + secret `nora-app-password`. Quando `rlsEnforce=true`: `DATASOURCE_USERNAME=nora_app` + `DATASOURCE_PASSWORD=nora-app-password` (KV) + `FLYWAY_DATASOURCE_USERNAME/PASSWORD` = admin + `NORA_RLS_ENFORCE=true` + caminho de telemetria BYPASSRLS (do 0026). Default OFF mantém prod como está.

### 5. Prova obrigatória: teste de integração do app sob enforce

Um teste (`RlsAppEnforcementIntegrationTest`, Testcontainers) **sobe o app sob enforce** (datasource = role NOBYPASSRLS, Flyway = admin, V020 aplicada) e valida, ponta-a-ponta: **signup, login, aceite de convite, verificação de email e reset de senha funcionam** E o isolamento cross-tenant **segura** (tenant A não vê `transcripts`/`meetings` de B). Esse teste é o "done" e a rede de segurança — o cutover ao vivo só acontece com ele verde.

### 6. Sequência de cutover (corrigida)

1. Mergear esta sub-fase (V020 + Flyway-admin + onboarding GUC + Bicep + teste verde no CI). Enforce ainda OFF — zero mudança em prod.
2. Provisionar roles: rodar `R001` como **admin** do Postgres (cria `nora_app` NOBYPASSRLS + `nora_telemetry` BYPASSRLS + grants). Popular `nora-app-password`, `rls-telemetry-password` no Key Vault.
3. Flipar no `bicepparam`: `rlsEnforce=true` + `rlsTelemetryDatasourceUrl` → deploy. A API passa a conectar como `nora_app`; Flyway segue como admin.
4. Smoke ao vivo: signup/login/convite/reset funcionam + 2 tenants isolados + painel operador ainda agregando.
5. Rollback trivial: `rlsEnforce=false` no bicepparam + redeploy (volta pro admin; schema fica).

## Consequências

**Positivas:**
- Fecha o furo crítico (PII de `transcripts` legível cross-tenant sob enforce) **sem quebrar auth**.
- Defesa em profundidade real onde importa (dados/PII/autz), com a única rede de segurança onde o app-filter pode falhar.
- Flyway-as-admin resolve DDL + owner-bypass de forma idiomática (config Spring, sem job separado).
- Cutover reversível e provado por teste de integração (não "torcer pra dar certo").

**Negativas / trade-offs:**
- Tabelas de identidade **não** têm rede de RLS — dependem do filtro aplicacional (já disciplinado). Aceito: auth é cross-tenant por design; alternativa (datasource bypass dedicado pro AuthService) é mais plumbing por ganho marginal nessas tabelas.
- Onboarding precisa setar o GUC explicitamente — acoplamento pequeno e localizado (signup/invite), coberto por teste.
- `V020` desabilita RLS em 6 tabelas que `V016`/`V019` habilitaram — registrado e justificado (não é regressão; é a correção do escopo).

## Alternativas Consideradas

1. **Datasource BYPASSRLS dedicado pro AuthService** (rotear `findByEmail` etc.) — correto mas exige 2º EntityManager/adapter; mais plumbing por ganho marginal (identidade não é o alvo de PII). Preterido em favor de exemptar identidade.
2. **Enforce em 100% (incl. identidade) + policies permissivas pra auth** — abriria as tabelas de identidade a quem não tem GUC (enumeração cross-tenant de users). Pior que escopo por dado.
3. **`nora_app` com `CREATE` no schema (Flyway como nora_app)** — `nora_app` viraria owner das tabelas futuras → bypass de RLS nelas (owner-exempt). Fura o próprio enforce. Preterido por Flyway-as-admin.
4. **Adiar pós-pitch** — o PO optou por fazer agora e direito (tempo disponível); o risco é mitigado pelo teste + enforce default OFF até o flip.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-06-04 | Arquiteto + Stratfy (PO) | Criação. Corrige o design de enforce do ADR 0026 após investigação achar 3 furos (Flyway-DDL, switch ausente, e auth quebrando sob enforce). Escopo por sensibilidade do dado (identidade com app-scope), Flyway-as-admin, onboarding seta GUC, Bicep switch, teste de app sob enforce como prova. Mantém V019 + R001 do 0026. |
