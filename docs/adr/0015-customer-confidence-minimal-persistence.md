# 0015 — Customer Confidence: persistência mínima viável na Sub-fase 1.11

- Status: aceito
- Data: 2026-05-14
- Decisores: Stratfy (PO), Tech Lead, Arquiteto Design
- Substitui parcialmente: ADR 0006 (Customer Confidence + Account Health) — escopo Account Health adiado

## Contexto

ADR 0006 (aceito em 2026-05-07) define Customer Confidence (por reunião) e Account Health (agregado). Schema completo está em `docs/api/llm-schemas/meeting-analysis-v1.schema.json:117-167` e Pydantic em `services/nlp-worker/src/nora_nlp/models.py` — worker JÁ EMITE `customerConfidence` quando reunião está vinculada a customer_account.

**Mas a persistência nunca foi implementada:**
- 0 migrations Flyway para `customer_accounts`, `customer_confidence_assessments`, `customer_buying_signals`, `customer_objections`, `meeting_account_links`, `account_health_snapshots`
- 0 endpoints REST que expõem Customer Confidence
- 0 UI no MeetingDetail

Resultado: **cada análise gera dados de Customer Confidence que são descartados** quando backend deserializa a resposta do worker (campo ignorado).

### Dívida narrativa

A landing pública do NORA (criada na Sub-fase 1.2 visual redesign v2) tem **HealthScoreSection** com chart visual e meta cards (Account / Owner / Stage) e events tooltip — **vende a feature visualmente**. Demo TOTVS:

1. Cliente entra no site público, vê chart Health Score
2. Clica "Entrar", autentica
3. Abre meeting → **não vê a feature**

Credibilidade afunda em 5 segundos. **Dívida narrativa = pior tipo de débito.**

### Audit pré-Sub-fase 1.10

Revisão do Arquiteto Design (2026-05-14) re-severizou de "Médio" pra **"Alta"** com voto pra implementar mínimo. Tech Lead concordou em revisão (`50-coordenacao-arquitetos/2026-05-14-de-tech-lead-para-arquiteto-design-resposta-revisao-audit.md`).

## Decisão

**Implementar persistência mínima de Customer Confidence na Sub-fase 1.11** com escopo deliberadamente reduzido:

### Escopo declarado (Sub-fase 1.11)

1. **Migration V013** `customer_confidence_persistence`:
   - `customer_accounts` (id, tenant_id, name, owner_user_id, stage, created_at, updated_at). Auto-criação on-the-fly por nome detectado pelo LLM
   - `meeting_account_links` (N:N reunião↔conta — uma reunião pode ter mais de uma conta, raro mas suportado)
   - `customer_confidence_assessments` (id, tenant_id, meeting_id, customer_account_id, score, band, trend nullable, rationale, created_at) — 1:1 com (meeting, account)
   - `customer_buying_signals` (id, assessment_id, type enum, quote, weight nullable, position)
   - `customer_objections` (id, assessment_id, type enum, quote, severity, competitor nullable, position)

2. **Domain models** em `services/api/src/main/java/com/nora/api/domain/customer/`: `CustomerAccount`, `MeetingAccountLink`, `CustomerConfidenceAssessment`, `BuyingSignal`, `Objection`, enums

3. **Application service** `CustomerConfidenceService`: persiste assessment quando worker retorna, gerencia auto-criação de `customer_accounts` por nome

4. **Mapping no worker proxy** `HttpNlpWorkerClient`: extrai `customerConfidence` da resposta do worker e passa pra service

5. **DTO + Response** `GET /meetings/{id}` expande retorno com `customerConfidence` quando presente (nullable)

6. **UI** Arquiteto Design: `CustomerConfidenceCard` no `MeetingDetail` (escopo dele)

### Escopo **NÃO incluído** (deferido via ADR 0014)

- **US50 Account Health Score agregado** — exige >5 meetings linkados a uma account pra ter sinal útil; espera pilot real
- **US51 Alerta mudança de banda** — depende de Health Score
- **CRUD manual de `customer_accounts`** — sem UI Account Manager no MVP
- **Endpoint dedicado** `GET /accounts/{id}/health` — não existe ainda

## Consequências

**Positivas:**
- **Dívida narrativa resolvida**: landing → demo → MeetingDetail mostram coerência. Cliente que entra vê o que prometido
- **Schema LLM existente é aproveitado**: worker já emite; só plumbing de backend pra persistir
- **Account Health pode evoluir** depois com dados reais acumulados (não no escudo do MVP)
- **MeetingDetail ganha card valioso** sem necessidade de feature gigante

**Negativas:**
- Account Health não implementado deixa US50-US51 abertos — explicitamente deferidos via ADR 0014
- Auto-criação de `customer_accounts` por nome é heurística — pode criar duplicatas (ex.: "Acme" e "ACME"). Mitigação: normalização de nome + dedup com `LOWER(name) = LOWER(:input)` + UI futura pra merge manual

## Alternativas Consideradas

1. **(a) Implementar mínimo viável** — **VOTO ESCOLHIDO**. Equilibra dívida narrativa × escopo gerenciável
2. **(b) Remover Customer Confidence da landing temporariamente + ADR sucessor de 0006 com "adiado, sem timeline"** — rejeitado. Esforço similar (~2h pra remover seções da landing) mas perde feature de vitrine; landing fica menos vendedora
3. **(c) Manter limbo (schema existe, nada persiste)** — rejeitado explicitamente pelo Arquiteto Design: "bomba sob a demo"

## Plano de Aplicação

Sub-fase 1.11 (Demo Polish Plano A), branch `feat/sub-1.11-customer-confidence-minimal`, esforço **M (~6-8h agentic)**. Sequência:

1. Migration V013 + domain
2. JPA entities + repos
3. Service + worker proxy update
4. DTO + endpoint expansion
5. Testes integration (auto-criação account, persist assessment, GET retorna confidence)
6. UI card (Arquiteto Design em paralelo)

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Joint (PO + Tech Lead + Arquiteto Design) | ADR criado. Voto Tech Lead + Design pra (a) implementa mínimo; Stratfy (PO) confirmou em bloco |
