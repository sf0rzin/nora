# Glossário — NORA

> Vocabulário canônico do projeto NORA. Termos ordenados alfabeticamente. Cada entrada inclui definição, escopo (onde aparece) e referência (ADR / arquivo / PR) quando aplicável.
>
> Use este doc quando:
> - Você é novo no projeto (humano ou Claude) e bate em termo desconhecido
> - Está escrevendo doc/ADR/spec e precisa de termo canônico
> - Está discutindo com a Stratfy (PO) / Arquitetos e quer alinhar vocabulário

---

## A

**Account Health Score** — Score agregado por **conta** (não por reunião), expressando saúde temporal do relacionamento com cliente/lead Enterprise. Escala 0-100 com bandas `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`. Calculado a partir de Customer Confidence + riscos + oportunidades. ADR 0006 aceito; implementação adiada via ADR 0014 (defer post-MVP). Reativação: pós-pilot com 3+ tenants tendo >10 reuniões por conta.

**Action Item** — Tarefa extraída automaticamente de reunião pelo LLM. Campos: `title`, `assignee` (opcional), `dueDate` (opcional), `priority` (`LOW`/`MEDIUM`/`HIGH`), `sourceQuote` (citação textual da fonte). Mostrada no painel `tasks/` e no detalhe da reunião. Editável pelo usuário (US24).

**ADR** — Architecture Decision Record. Decisão técnica durável + contexto + alternativas consideradas. **Imutável** uma vez aceita — sucessor cria novo ADR, não edita o antigo. Formato MADR enxuto (Status / Data / Decisores / Contexto / Decisão / Consequências / Alternativas). Em `docs/adr/`. Índice: `docs/adr/README.md`.

**AUTH_FILTER_HARD_CAP** — Constante `500` em `MeetingsController.java:67` que limita quantas reuniões são carregadas em memória antes da filtragem IAM. Débito conhecido **ainda aberto** (2026-05-21): tenants com >500 reuniões têm páginas vazias e `totalItems` truncado. Fix era alvo da Sub-fase 1.11 (não iniciada) via empurrar predicado IAM pra SQL (JSONB GIN em `meeting_attributes`).

## B

**Banda** — Classificação categórica de scores em buckets discretos. Bandas padronizadas:
- **Productivity Score** e **Customer Confidence**: `LOW` / `MEDIUM` / `HIGH`
- **Account Health**: `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`

**BlackHole** — Driver de áudio virtual macOS usado pelo Desktop NORA pra capturar áudio do sistema (workaround pre-ScreenCaptureKit). Adicionado em PR #37. Contradiz documentação anterior que dizia "Não suporta macOS no MVP".

**Bucket4j** — Lib Java (versão 8.10.1) usada pra rate limiting no backend. Usada principalmente no `SpeechController` pra evitar abuse do Speech Token Broker (que custa dinheiro Azure).

## C

**Container Apps** — Serviço Azure usado pra hospedar `nora-api-dev`, `nora-worker-dev` e `nora-web-dev`. Ambiente único `nora-cae-dev`. Worker é internal-only (não exposto ingressante).

**Conditions** — Em IAM Policy, regras opcionais que restringem quando um statement Allow/Deny aplica. Formato AWS-style: `{ "stringEquals": { "nora:Department": "sales" } }`. PolicyEvaluator atual suporta só `StringEquals`; `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` planejados pra Sub-fase 1.11. Operadores não-suportados resultam em `false` (fail-closed).

**Coverage** — (1) Em **Productivity Score**, status de cada `expectedOutcome` declarado pelo usuário: `ADDRESSED` (cobriu integralmente) / `PARTIAL` (cobriu parcialmente) / `MISSED` (não cobriu). Cada um com evidência textual (`sourceQuote`). (2) Em **testes**, percentual de código exercitado pelos testes — worker NLP 87%, backend Spring 67%, web 0% (sem runner).

**Customer Confidence** — Score 0-100 da **confiança do cliente/lead na NORA do tenant** (não a nossa confiança no cliente). Por reunião. Combina sentimento + sinais de compra (`buyingSignals`) + objeções (`objections`) + tendência em relação à última reunião da mesma conta. Banda `LOW`/`MEDIUM`/`HIGH`. Tendência `IMPROVING`/`STABLE`/`DECLINING`. ADR 0006 aceito; schema LLM existe; persistência adiada (US48-49 PARTIAL); ADR 0015 (Sub-fase 1.11) decide entre implementar mínimo viável vs remover da landing.

## D

**DDD** — Domain-Driven Design. Padrão arquitetural usado no backend Spring. Camadas:
- **domain**: regras puras (entidades, value objects). Não depende de Spring, DB, HTTP, SDKs externos
- **application**: orquestração (services). Coordena domain + infra
- **infrastructure**: adapters externos (JPA repositories, HTTP clients, message senders)
- **api**: HTTP/REST (controllers). Thin — só translation HTTP↔application

**Deny-first eval** — Estratégia de avaliação IAM onde `Deny` explícito vence sobre `Allow` aplicável. Ordem completa: (1) Root bypass → (2) Deny explícito → (3) Allow aplicável → (4) Default Deny.

## E

**Effect** — Em IAM Policy, campo do statement com valor `Allow` ou `Deny`. `Deny` vence sobre `Allow` (ver Deny-first eval).

**expectedOutcome** — Declaração do usuário sobre o que ele esperava que a reunião resolvesse. Parte de `MeetingGoal`. Lista de strings curtas (e.g., "Decidir se vamos comprar Protheus", "Alinhar pricing com cliente X"). Productivity Score mede cobertura de cada um.

## F

**Federated Credential** — Mecanismo OIDC do Azure pra autenticar GitHub Actions sem armazenar client secret. Service Principal `sp-nora-github-deploy` tem 3 credenciais federadas separadas: (main) / (pull_request) / (environment:dev). Lição: precisa fed cred por par (branch, environment).

**Flyway** — Ferramenta de migrations SQL versionadas. Migrations em `services/api/src/main/resources/db/migration/V001__*.sql` até V012 (em 2026-05-14). Cada migration é imutável após mergeada em main.

## G

**GHCR** — GitHub Container Registry. Armazena imagens Docker `ghcr.io/sys0xff/nora-{api,worker,web}:{latest, sha-XXXXXXX, ref}`. Imagens Public (passo manual nas settings do GHCR). Build via `build-images.yml`.

## I

**IAM AWS-style** — Modelo de IAM do NORA inspirado em AWS IAM: **Root** + **Users** + **Groups** + **Policies** com Effect/Action/Resource[/Condition]. **Sem role hierarchy hardcoded** — o tenant cria seus próprios grupos. ADR 0007.

**iam_policy_versions** — Tabela (migration V006) que mantém **versionamento imutável** de policies. Cada alteração de policy cria nova versão; versão antiga fica como histórico. Tem `is_template` planejado mas ainda não em V006 (US41 MISSING).

## J

**JJWT** — Lib Java (versão 0.12.6) usada pra emitir e validar JWTs no backend. Configurada em `services/api/pom.xml`.

**JSON Schema strict** — Formato obrigatório de validação na saída do LLM. Passamos `response_format={"type": "json_schema", ...}` pra OpenAI/Azure OpenAI; o provedor garante que a resposta valida contra o schema. Sem isso, parsing pode quebrar. ADR 0003. Schema canônico em `docs/api/llm-schemas/meeting-analysis-v1.schema.json`.

## K

**Key Vault** — Serviço Azure usado pra armazenar secrets (JWT_SECRET, OPENAI_API_KEY, ConnectionString Postgres, Speech key). Container Apps acessa via **Key Vault references** com User-Assigned Identity (UAI). Nome no dev: `nora-kv-dev-wgl3a3`. Soft-delete bloqueia recriação por 7 dias (pegadinha Azure for Students).

## L

**Live Analysis** — Análise em tempo real durante reunião (Desktop captura áudio + sidecar Python transcreve + worker NLP devolve highlights). Endpoint `POST /meetings/live-analyze`. Schema `LiveHighlightsV1`. PR #65.

**LLM** — Large Language Model. No NORA, default OpenAI `gpt-4o-mini` (configurável via env por tenant, agnostic pelo ADR 0004). Em Enterprise pode ser Azure OpenAI. Saída obrigatória via JSON Schema strict (ADR 0003).

## M

**MeetingGoal** — Input **opt-in** do usuário pra calcular Productivity Score. Campos:
- `purpose` (string curta) — propósito declarado da reunião
- `expectedOutcomes` (lista) — o que precisava ser resolvido/decidido
- `projectStateSnapshot` (opcional) — estado do projeto pra contexto

Submetido via `PUT /meetings/{id}/goal`. ADR 0005.

**MoSCoW** — Acrônimo de priorização: **M**ust have / **S**hould have / **C**ould have / **W**on't have (v1). Usado no backlog (`docs/product/backlog.md`). 31 Must, 14 Should, 5 Could, 7 Won't no MVP original.

**Multi-tenancy** — Isolamento de dados entre clientes (tenants) do NORA. **No MVP**: `tenant_id` em toda tabela tenant-owned + filtro no application layer (Spring). **Defesa em profundidade**: RLS Postgres (V016, enforce opt-in) + FK composta de isolamento (V015). ADR 0002.

## N

**NlpWorker / Worker NLP** — Serviço Python FastAPI em `services/nlp-worker/` que processa transcripts. Pipeline:
1. PII Shield redige PII
2. TF-IDF baseline extrai termos importantes (interpretável)
3. LLM analisa com prompt + tenant context + schema strict
4. Validação Pydantic da saída

Internal-only — só backend Spring fala com ele. Hosted em `nora-worker-dev` (Container App internal).

**Negative list** — Lista de termos que **não** devem ser redigidos pelo PII Shield mesmo que pareçam nomes (e.g., "Apolo" parece nome próprio mas é referência mitológica/comum no contexto técnico; nomes próprios de membros da equipe que aparecem em commits e comentários idem). ~80 termos catalogados pra reduzir false positives. ADR 0012.

## O

**OIDC** — OpenID Connect. Usado pelo Service Principal Azure (`sp-nora-github-deploy`) pra autenticar GitHub Actions sem client secret. Configurado via federated credentials no app registration.

**Outcome** — Ver `expectedOutcome`.

## P

**packages/nlp-baseline** — Package Python local em `packages/nlp-baseline/` com 3 módulos TF-IDF (preprocessing, vectorizer, top_terms). Usado pelo worker NLP **antes** do LLM pra extrair termos importantes de forma interpretável. ADR 0010.

**PII** — Personally Identifiable Information. Categorias cobertas pelo PII Shield do NORA: email, CPF, CNPJ, phone, credit card, PERSON_NAME (BR). Não cobertos no MVP: ADDRESS (débito catalogado). ADR 0012.

**PII Shield** — Sistema do worker NLP que detecta e redige PII **antes** de mandar texto pra LLM externo. Substitui por placeholders `[[TIPO_N]]` (e.g., `[[EMAIL_1]]`, `[[CPF_2]]`). Pós-LLM o backend pode unredact se autorizado. ADR 0012. Implementação em `services/nlp-worker/src/.../pii_shield.py` (95% coverage).

**PolicyEvaluator** — Componente Spring em `services/api/src/main/java/.../PolicyEvaluator.java` que recebe um conjunto de policies + contexto (user, action, resource, attributes) e retorna `Allow` / `Deny`. Implementa Deny-first eval. Operador suportado hoje: `StringEquals`. Operadores planejados pra 1.11: `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan`. Cobertura 95.8% instr / 84% branches.

**Productivity Score** — Score 0-100 da **reunião contra o objetivo declarado** pelo próprio usuário (não benchmark externo). **Opt-in** por reunião — sem `MeetingGoal` não calcula. Banda `LOW`/`MEDIUM`/`HIGH`. ADR 0005. Implementação full-stack: V012 + worker (model + stub + LLM) + backend Spring + web (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`).

## R

**RAG** — Retrieval-Augmented Generation. Padrão de IA onde o prompt do LLM é enriquecido com documentos relevantes recuperados de uma base de conhecimento. No NORA, usado pra trazer **contexto do tenant** (produtos, glossário, concorrentes, stakeholders) ao prompt. **MVP**: stub local (injeta contexto inline no prompt). **Produção**: Azure AI Search com índice por tenant (flag `enableSearch` no Bicep, hoje `false`).

**Refresh token** — Token de **longa duração** (30 dias, UUID stateful) usado pra renovar o JWT de access (15min). Persistido em `iam_refresh_tokens` (migration V011). Cookie httpOnly `nora_refresh`. Acesso curto + refresh longo = padrão de segurança balanceado.

**rg-nora-dev** — Resource Group Azure de desenvolvimento. Subscription `Azure for Students`. Region `centralus`. Contém 14 recursos. Custo estimado R$110-180/mês.

**rg-nora-prod** — Resource Group Azure de produção. **Ainda não existe** — criação planejada pra Sub-fase 1.12 (Production Hardening). Isolamento total do dev.

**RLS** — Row-Level Security. Recurso do Postgres que filtra rows por policy SQL. **Entregue no schema em V016** (`tenant_isolation` em 10 tabelas; predicado `tenant_id = nora.current_tenant_id()`, lendo o GUC `nora.current_tenant_id` setado pelo `TenantRlsAspect`). **Enforcement opt-in:** owner/admin bypassa por default (dev/testes inertes); em prod, ativar via role `nora_app` (`NOBYPASSRLS`) + flag `nora.security.rls.enforce`. Defesa em profundidade do filtro de app (ADR 0002). (Nota: o GUC real é `nora.current_tenant_id`, não `app.tenant_id` como o ADR 0002 esboçou.)

## S

**Schema strict** — Ver JSON Schema strict.

**Service Principal** — Identity de aplicação Azure usada pra autenticar pipelines/scripts. NORA usa `sp-nora-github-deploy` (appId `3f8b27f6-...`) com roles `Contributor` + `Role Based Access Control Administrator` em `rg-nora-dev`. Autenticação via OIDC (federated credentials).

**Soft-delete** — Comportamento padrão de Key Vault e Cognitive Services (Azure Speech) onde recursos deletados ficam recuperáveis por 7 dias. Bloqueia recriação com mesmo nome. Fix: `az keyvault purge` / `az cognitiveservices account purge`. Pegadinha #4 e #5 do Azure for Students.

**Speech Token Broker** — Endpoint backend (`SpeechController`) que emite token efêmero Azure Speech (~9 minutos) pro Desktop. **Não expõe a Speech key** — só o token. Rate limit Bucket4j. ADR 0009.

**Sub-fase** — Unidade de trabalho do NORA. Numeração `X.Y` (e.g., `1.10`). Cada sub-fase = 1+ PRs mergeados + entrega coerente e verificável. Sub-fase fecha quando escopo entrega, não quando timer estoura. Roadmap completo em `docs/product/roadmap.md`.

## T

**Tenant** — Cliente/organização que usa o NORA. Isolamento total garantido por `tenant_id` em todas as tabelas tenant-owned. Cada tenant tem seus próprios Users, Meetings, Tasks, IAM Policies, Tenant Context, Refresh Tokens, Audit Events.

**Tenant Context** — Configuração por tenant que ensina o NORA o "vocabulário da empresa". Campos: nome da empresa, produtos, glossário, concorrentes, stakeholders. Injetado no prompt do LLM em toda análise. Editado via `TenantContextController`. Migration V005. US31 (histórico de versões) ainda MISSING.

**TF-IDF baseline** — Term Frequency × Inverse Document Frequency. Algoritmo clássico de NLP que extrai termos importantes de um documento comparando frequência local vs corpus. Package `packages/nlp-baseline/` extrai termos relevantes pré-LLM (interpretável e barato). ADR 0010.

## U

**UAI** — User-Assigned Identity. Tipo de managed identity Azure **pré-criada** (vs SystemAssigned que é criada com o recurso). NORA usa duas UAIs (`nora-uai-deploy` e `nora-uai-app`) pra resolver ciclo de role assignment + KV reference no Container Apps. Sem isso, ACA tenta acessar KV antes da role assignment ser propagada.

**Unredact** — Operação de reverter placeholders `[[TIPO_N]]` do PII Shield de volta pros valores originais. Feito pelo backend após resposta do LLM, só se autorizado pelo contexto do request.

## V

**V001 - V016** — Migrations Flyway atuais (até 2026-05-21). Cada uma idempotente e imutável, numeração sequencial. V013 = soft-delete, V014 = refresh-token rotation, V015 = composite FK de isolamento, V016 = Row-Level Security. **Nota:** o ADR 0015 reservara "V013" para Customer Confidence, mas o slot virou soft-delete e a persistência de Customer Confidence segue inexistente.

## W

**Wildcard** — Em IAM Policy, `*` em Resource ou Action permite qualquer match. Exemplos:
- `nora:tenant:acme:meeting/*` permite qualquer meeting do tenant `acme`
- `meeting:*` permite qualquer action de meeting (read, write, update, reprocess, analyze:live)
- `*` permite tudo (uso típico em Root policy)

Implementado em PolicyEvaluator com glob-style matching.

**WireMock** — Lib Java (versão standalone 3.9.1) usada nos testes de integração do backend pra mockar respostas HTTP externas (worker NLP, Azure Speech, OpenAI). Permite teste sem rede.

**Worker NLP** — Ver NlpWorker.

---

## Histórico do Documento

| Versão | Data | Descrição |
|---|---|---|
| 1.0 | 2026-05-14 | **Criação inicial**. Glossário canônico cobrindo termos de produto (Customer Confidence, Productivity Score, Account Health, MoSCoW, Tenant), arquitetura (DDD, RAG, JSON Schema strict, Multi-tenancy, RLS), IAM (IAM AWS-style, Effect, Conditions, Wildcard, Deny-first eval, PolicyEvaluator), infra Azure (Container Apps, Key Vault, UAI, OIDC, Soft-delete, Service Principal, rg-nora-dev), implementação (NlpWorker, PII Shield, TF-IDF baseline, packages/nlp-baseline, Speech Token Broker, Refresh token), e processo (ADR, Sub-fase, Flyway/V001-V012, BlackHole, AUTH_FILTER_HARD_CAP). 50+ termos no total |
