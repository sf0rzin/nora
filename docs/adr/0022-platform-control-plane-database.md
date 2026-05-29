# 0022 — Banco de plataforma separado + 2º datasource (control plane)

- Status: aceito
- Data: 2026-05-28
- Decisores: Co-arquitetos (Opus) + Stratfy (PO/dono)
- Relacionado: ADR 0002 / 0019 (multi-tenancy + RLS), ADR 0016 (separação rg-nora-prod)

## Contexto

O NORA vai ganhar um **control plane de operador** (só pros donos da plataforma — nenhum cliente
acessa) com catálogo de modelos LLM, seleção de modelo por serviço em runtime e **telemetria de
custo de IA** (tokens/custo por tenant/modelo/serviço). Isso é escopo **net-new** — não existia no
backlog nem no vocabulário de produto.

Há uma tensão estrutural com o isolamento por tenant já estabelecido:

1. **Toda tabela tenant-owned carrega `tenant_id` + RLS** (ADR 0002/0019). O `TenantRlsAspect` faz
   `SET LOCAL nora.current_tenant_id` em **toda** `@Transactional`, e `nora.current_tenant_id()`
   retorna `NULL` quando não setado ⇒ fail-closed (0 rows). Um **catálogo de modelos é global** (sem
   tenant); colocá-lo no mesmo schema/role faria queries do control plane retornarem 0 rows ou
   exigiria furar a invariante "toda tabela é tenant-owned".
2. **Telemetria de custo é cross-tenant por natureza** (visão do dono): agregar custo por tenant
   conflita com RLS, que existe justamente pra esconder dados cross-tenant.
3. **Blast radius**: config de plataforma e telemetria têm ciclo de vida e criticidade diferentes do
   dado transacional do cliente. Isolar protege um do outro — e é argumento de pitch.

A API hoje é **single datasource por autoconfig puro do Spring Boot** (zero `@Configuration` de
`DataSource`/`EntityManagerFactory`/`@EnableJpaRepositories`).

## Decisão

1. **Banco Postgres fisicamente separado** pra plataforma (`nora-pg-platform-*`), um Flexible Server
   próprio B1ms Burstable em `centralus` (mesmas restrições do Azure for Students). Não é um schema
   nem um database adicional no servidor existente — é servidor separado, pra blast radius real.
2. **Segundo datasource na API Spring acessado via `JdbcTemplate`** (não um 2º
   `EntityManagerFactory` JPA). Como não há config explícita de datasource hoje, introduzir um 2º EMF
   forçaria tornar o primário `@Primary` + segmentar `@EnableJpaRepositories` por pacote — mexendo no
   que já roda e arriscando os ITs Testcontainers. Um `NamedParameterJdbcTemplate` dedicado
   (`PLATFORM_DATASOURCE_*`), no estilo do `IamRepositoryAdapter` (SQL nativo), tem **blast radius
   zero** sobre o JPA primário.
3. **Apenas a API Spring abre conexão no banco de plataforma.** Worker e BFF nunca conectam direto;
   consomem via contratos HTTP (`/internal/platform/*`, `/admin/platform/*`). Centraliza acesso,
   evita N pools e N cópias de credencial.
4. **Módulo gated + soft-fail.** O módulo platform é `@ConditionalOnProperty(nora.platform.enabled)`
   (default `false` em local/test/CI — não conecta, não roda Flyway). O Flyway do banco de plataforma
   é rodado num `ApplicationRunner` com `try/catch`: se o banco estiver fora, a **API continua
   subindo** (modo degradado) — o caminho do cliente (datasource primário) não pode cair por causa do
   control plane.
5. **Flyway próprio** (`classpath:db/platform`, history table própria no banco separado), wired por
   bean dedicado — o autoconfig do Boot só roda Flyway no datasource primário.
6. As tabelas de plataforma **não têm `tenant_id` como fronteira de segurança**. `usage_events`
   carrega `tenant_id` apenas como **dimensão de telemetria** (UUID solto, sem FK, sem RLS).

## Consequências

**Positivas:**
- Isolamento real (blast radius) entre dado do cliente e config/telemetria do dono.
- A invariante "toda tabela tenant-owned tem `tenant_id`+RLS" do banco primário fica intacta.
- JdbcTemplate-only não toca o JPA primário ⇒ ITs existentes não regridem.
- Soft-fail: control plane fora ≠ NORA fora.

**Negativas / trade-offs:**
- **~2× o custo de DB** (segundo Flexible Server). Aceito como argumento de pitch; crédito Azure for
  Students monitorado — se apertar antes de 12/06, pausa-se o que for não-essencial.
- Duas fontes de verdade de schema (dois Flyway). Mitigado: history tables independentes, locations
  distintas.
- Agregação cross-tenant de "métricas de negócio" (telemetria c) lê o banco **primário** sem contexto
  de tenant (bypass de RLS) — read-path dedicado, operador-only, explicitamente comentado. Cortável.

## Alternativas Consideradas

1. **Mesmo banco, schema `platform`** — rejeitado: o `TenantRlsAspect` roda em toda tx; tabelas sem
   `tenant_id` exigiriam exceções de policy, furando a invariante. E sem blast radius.
2. **2º database no mesmo Flexible Server** — mais barato, mas sem isolamento operacional real (um
   servidor = um ponto de falha/credencial). Rejeitado pela decisão de blast radius.
3. **2º EntityManagerFactory JPA** — rejeitado: força reconfigurar o datasource primário (hoje
   implícito), alto risco sobre ITs. JdbcTemplate cobre o CRUD simples do control plane.
4. **Cada serviço (worker/BFF) conectando direto no banco de plataforma** — rejeitado: N pools, N
   cópias de credencial, acoplamento. Centralizado na API.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-28 | Co-arquitetos + Stratfy | Criação. Exceção consciente ao ADR 0014 (freeze de escopo), autorizada pela Stratfy: control plane + telemetria entram pré-pitch. |
