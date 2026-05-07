# Nora — Negotiation Observability & Revenue Assistant

> Plataforma de inteligência conversacional que transforma qualquer reunião de trabalho em decisões, ações e receita — com contexto do seu negócio, não de mais ninguém.

**Projeto:** FIAP Challenge 2026 × TOTVS — Engenharia de Software (2º ano)
**Meta:** Vencer o NEXT FIAP 2026.
**Time:** Anthony (Tech Lead) + 3 integrantes
**Status:** Discovery concluído / Arquitetura e padrões definidos — iniciando build do MVP

---

## 1. O Problema

Qualquer profissional que participa de reuniões enfrenta a mesma perda: o conhecimento gerado na conversa — decisões, oportunidades, riscos, compromissos — **morre na memória dos participantes** ou em anotações desconexas. Para times comerciais, isso se traduz em oportunidades não capturadas, churn não detectado e CRMs subpreenchidos. Para times de produto e engenharia, em tasks perdidas e alinhamento quebrado.

Ferramentas de Sales Intelligence existentes (Gong, Clari) são caras, em inglês e sem conhecimento do negócio específico de cada empresa. Ferramentas de transcrição (Fireflies, Otter.ai) capturam texto mas não extraem inteligência. **Nenhuma solução se adapta ao contexto proprietário da empresa.**

## 2. A Solução

**NORA** é uma plataforma de inteligência conversacional com três superfícies integradas:

### 2.1 Três Superfícies, Um Motor

```
┌──────────────────┬───────────────────────┬───────────────────────┐
│   NORA  WEB      │    NORA  DESKTOP       │     NORA  API / MCP   │
│  (pós-reunião)   │  (tempo real + pós)    │  (integração externa) │
├──────────────────┼───────────────────────┼───────────────────────┤
│ Upload texto /   │ Tauri (Rust)           │ MCP Servers:          │
│ áudio            │ Captura WASAPI         │  · Google Calendar    │
│ Dashboard        │ Streaming transcrição  │  · Linear / Jira      │
│ Health Score     │ Coaching ao vivo       │  · GitHub             │
│ Admin IAM        │ Sync automático        │  · Salesforce/HubSpot │
│ Admin Catálogo   │ para o web             │                       │
└──────────────────┴───────────────────────┴───────────────────────┘
              mesmo backend · mesmo motor IA · mesmo banco
```

### 2.2 Product Context System — o diferencial horizontal

O NORA não tem conhecimento hardcoded de nenhuma empresa. Cada tenant configura o próprio contexto:

```
Admin da empresa configura:
├── Catálogo de produtos (nome, descrição, casos de uso, diferenciais)
├── Lista de concorrentes com contexto de mercado
└── Glossário de termos e processos internos
         │
         ▼  (armazenado como embeddings — OpenAI Embeddings no MVP, Azure AI Search em Enterprise)
         │
NLPWorker na análise:
├── RAG retrieval sobre o catálogo do tenant
├── Injeta no system prompt: contexto específico da empresa
└── LLM extrai oportunidades/riscos com vocabulário da empresa
```

**Resultado:** a TOTVS configura Protheus/RM/Fluig. Uma fintech configura os produtos dela. Uma distribuidora configura os dela. **Mesma plataforma, inteligência contextualizada para cada negócio.**

### 2.3 Saídas por perfil

**NORA Core (individual):**
- Resumo estruturado da reunião
- Action items detectados e categorizados
- Rastreamento de projetos ao longo do tempo
- Push automático via MCPs para as ferramentas do usuário

**NORA Enterprise (times comerciais):**
- Oportunidades de venda com produto e probabilidade estimada
- Sinais de churn/insatisfação com contexto
- Competitive Radar — menções a concorrentes com estágio (avaliando/comparando/contratado)
- **Customer Confidence Score (por reunião)** — confiança do cliente/lead em nós, baseada em sinais de compra e objeções; alimenta o Account Health Score
- Account Health Score temporal — saúde da conta agregada ao longo de múltiplas reuniões
- Next Best Action — recomendação de ação concreta nas próximas 48–72h
- Persona & Sentimento multi-aspecto por tópico

### 2.4 Diferenciais competitivos

1. **Product Context via RAG** — inteligência calibrada ao negócio de cada tenant, não genérica.
2. **Account Health Score temporal** — detecta degradação de sentimento ANTES do churn.
3. **Desktop real-time** — coaching ao vivo durante a reunião, não só análise pós.
4. **IAM enterprise-grade** — controle granular de quem vê o quê (RBAC + ABAC).
5. **PII Shield / LGPD-first** — redação automática de dados sensíveis antes de qualquer LLM.

## 3. Público-alvo

**NORA Core:**
- Profissionais individuais (devs, PMs, consultores) que querem clareza e ações definidas saindo de reuniões.

**NORA Enterprise:**
- Times comerciais B2B com ciclos de venda complexos (mercado inicial: ecossistema TOTVS).
- Gerentes Comerciais que precisam de visão consolidada de pipeline e saúde de contas.
- Customer Success com foco em retenção proativa.

## 4. Arquitetura (visão de alto nível)

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│  NORA WEB                    │   │  NORA DESKTOP (Tauri/Rust)   │
│  Next.js 14 + shadcn/ui      │   │  Windows: WASAPI loopback    │
│  Azure Static Web Apps       │   │  Streaming → Azure AI Speech │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │ HTTPS                             │ HTTPS
               └─────────────┬─────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│  API Gateway (Azure APIM)                                         │
│  WAF · Rate Limiting · JWT Validation · Tenant Routing            │
└────────────────────────────┬─────────────────────────────────────┘
                             │
          ┌──────────────────┴──────────────────┐
          │                                     │
┌─────────▼──────────────┐          ┌───────────▼─────────────────┐
│  Backend (Spring Boot) │          │  Auth: App JWT + Entra ID   │
│  Java 21 + DDD         │          │  E-mail/senha · SSO · JWT   │
│  REST + OpenAPI        │          └─────────────────────────────┘
│  Domain core           │
│  IAM Middleware        │  ← intercepta toda query, injeta filtros
│  RBAC + ABAC engine    │    ABAC (department, project, account)
│  Multi-tenant (RLS)    │
└─────────┬──────────────┘
          │ enqueue
┌─────────▼──────────────┐
│  Azure Service Bus     │
└─────────┬──────────────┘
          │
┌─────────▼──────────────────────────────────────────────────────┐
│  Worker NLP (Python + FastAPI · Azure Container Apps)           │
│  Pipeline:                                                       │
│   1. PII Shield (presidio + regex BR)                            │
│   2. Limpeza textual (lowercase, pontuação, stopwords)           │
│   3. TF-IDF (baseline interpretável — exigência DS Sprint 1+2)   │
│   4. RAG retrieval → Product Context do tenant                   │
│   5. Embeddings (OpenAI text-embedding-3-small no MVP)            │
│   6. Extração estruturada via LLM Provider agnóstico              │
│      (default OpenAI gpt-4o-mini; Azure OpenAI em Enterprise)     │
│      response_format=json_schema strict + validação jsonschema    │
│   7. Account Health scoring (temporal, por tenant)               │
└─────────┬──────────────────────────────────────────────────────┘
          │
┌─────────▼──────────────────────────────────────────────────────┐
│  Persistência                                                    │
│  · Postgres (Azure DB) — domínio, RLS multi-tenant              │
│    · tenants, users, roles, policies (IAM)                      │
│    · product_catalog (por tenant — base do RAG)                 │
│    · meetings, transcriptions, analyses, health_scores          │
│  · Blob Storage — áudios temporários (TTL) + transcrições brutas │
│    criptografadas                                                │
│  · Azure AI Search — embeddings do catálogo + histórico contas  │
│  · Key Vault — segredos, API keys, CMK opcional                 │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  MCP Servers (Node.js · deployados como Azure Functions)        │
│  · nora-mcp-calendar  → Google Calendar / Outlook              │
│  · nora-mcp-tasks     → Linear / Jira                          │
│  · nora-mcp-github    → GitHub Issues / PRs                    │
│  · nora-mcp-crm       → Salesforce / HubSpot                   │
└────────────────────────────────────────────────────────────────┘

Observabilidade transversal: App Insights + OpenTelemetry + structured logging
```

## 5. Estratégia: Acadêmico ↔ Produto Real

Nada é throw-away. Cada entrega da rubrica vira artefato de produção:

| Disciplina           | Entrega acadêmica                                   | Vira no produto                                              |
| -------------------- | --------------------------------------------------- | ------------------------------------------------------------ |
| Agile/Squad          | Visão, persona, backlog, casos de uso, vídeo pitch  | Documentação real + landing page                             |
| Data Science         | Notebook TF-IDF + EDA + insights                    | Baseline interpretável do worker NLP (validação pré-LLM)     |
| Database Design      | Modelo Oracle (Logical + Relational) compatível UML | Schema real portado para Postgres                            |
| DDD-Java             | Projeto Java OO + UML + Scanner                     | **Núcleo do domínio do backend Spring Boot real**            |

## 6. Roadmap de Sprints

### Sprint 1 + 2 — Entrega 12/06/2026 (MVP de Visão + Núcleo NLP + Domínio)
- Visão de produto consolidada
- Notebook NLP com TF-IDF + EDA + insights sobre dataset de transcrições
- Modelagem de dados (Oracle) compatível com domínio Java
- Núcleo Java DDD com entidades, value objects, aggregates do domínio
- Pitch em vídeo de 5 min

### Sequência de Build (pós Sprint 1+2)

| # | Componente | Estratégia | Justificativa |
|---|---|---|---|
| 1 | Infra base + Auth e-mail/senha + Multi-tenant | BUILD | Fundação do MVP; SSO Entra ID entra como evolução Enterprise |
| 2 | Worker NLP + RAG + Product Context | BUILD | Coração do produto |
| 3 | Web: upload texto + análise + dashboard | BUILD | Core do produto, atende rubrica |
| 4 | IAM: RBAC + ABAC + tela de admin | BUILD | Requisito enterprise real |
| 5 | MCP servers (Linear/Jira + Calendar) | BUILD | ~200 linhas cada, alto impacto no pitch |
| 6 | Web: upload áudio (Azure AI Speech) | BUILD | Adiciona superfície sem mudar arquitetura |
| 7 | Desktop: Tauri + WASAPI + streaming | BUILD (Windows pós-MVP) | Demo avançada depois que Web + Worker estiverem estáveis |
| 8 | Polimento UX + landing page | BUILD | Cara de produto real |
| 9 | iOS/Android | DESIGN only | Fora do escopo realista |
| 10 | Integração TOTVS CRM nativa | DESIGN only | Depende de API TOTVS sem acesso |

## 7. Cloud & Stack

| Camada | Tecnologia | Justificativa |
|---|---|---|
| Cloud | Azure | Parceria Microsoft × TOTVS; deploy planejado em Container Apps + Azure DB |
| Backend | Java 21 + Spring Boot 3 | Atende DDD-Java + é o backend real de produção |
| Worker NLP | Python 3.12 + FastAPI | Ecossistema NLP/ML; Container Apps para escalar independente |
| Frontend Web | Next.js 14 + TypeScript + shadcn/ui + Tailwind | SSR, acessível (WCAG AA), DX moderno |
| Desktop | Tauri 2 (Rust) | Mais leve que Electron, acesso nativo WASAPI, bundle < 10MB |
| Banco de dados | Postgres 16 (Azure DB Flexible Server) | RLS nativo para multi-tenancy |
| LLM | **Provider agnóstico** (API Chat Completions) — default **OpenAI `gpt-4o-mini`** no MVP; Azure OpenAI em Enterprise quando aprovado; Groq/OpenRouter como fallback. Ver ADR 0004. | Desbloqueia o time sem aprovação Azure pendente; mantém portabilidade total via `LLM_BASE_URL`/`LLM_API_KEY` |
| Embeddings | OpenAI `text-embedding-3-small` no MVP; Azure OpenAI em Enterprise | Mesmo princípio agnóstico do LLM |
| Transcrição | Azure AI Speech (PT-BR, diarização) | Melhor diarização PT-BR do mercado |
| RAG / Search | Azure AI Search | Vetorial + full-text, integrado ao ecossistema |
| MCPs | Node.js 22 + Azure Functions | Serverless, escala zero, padrão MCP 1.0 |
| Auth | JWT/OAuth2 próprio no MVP + Microsoft Entra ID no Enterprise | Login e-mail/senha para velocidade; SSO/SAML quando tenant Enterprise exigir |
| Segredos | `.env` no dev, GitHub Actions Secrets em CI, Azure Key Vault em produção | Zero credencial em código |
| Observabilidade | App Insights + OpenTelemetry | Rastreio distribuído ponta a ponta |
| IaC | Bicep + GitHub Actions | Infra reproduzível, CI/CD auditável |

## 8. IAM — Identity & Access Management

Modelo híbrido **RBAC + ABAC**, inspirado no AWS IAM:

```
Tenant (empresa)
├── Root / Owner       — acesso irrestrito, gerencia billing e org
├── Admin              — gerencia usuários/roles/policies, acesso configurável
├── Roles (RBAC)
│   ├── Viewer         — lê transcrições e análises
│   ├── Analyst        — lê + exporta + comenta
│   └── Manager        — lê, exporta, vê Health Score e dashboards do time
└── Policies (ABAC)    — filtram o que cada role enxerga
    ├── department:     ["sales", "design", "product"]
    ├── project:        ["proj-alpha", "proj-beta"]
    └── client_account: ["conta-XYZ", "conta-ABC"]
```

**Exemplo:** Diretor de Design → role `Manager` + policy `department: design`
→ Gerencia o time de design, mas **nunca vê** uma transcrição de reunião de vendas.

**Efeito no Product Context:** cada departamento pode ter subcatálogo próprio.
A IA usa o contexto correto baseado nas policies de quem analisa.

**Efeito no Desktop:** o app já sabe as policies do usuário logado — a transcrição nasce tagueada com `department`, `project`, `participants` desde a captura.

## 9. Segurança & Compliance

- OWASP Top 10 endereçado item a item (documentado)
- LGPD by design: PII detection/redaction pré-LLM, consentimento explícito, direito ao esquecimento
- Multi-tenant isolation via Postgres Row-Level Security
- IAM middleware no backend intercepta toda query — filtros ABAC aplicados automaticamente
- Audit log append-only: quem acessou qual transcrição, quando, de qual IP
- Secrets em Azure Key Vault (zero credencial em código ou variável de ambiente não gerenciada)
- TLS 1.3 em trânsito, AES-256 em repouso, CMK opcional para enterprise
- Rate limiting + WAF no APIM

## 10. Próximos passos

- [x] Visão do Produto + Quadros É-Não É / Faz-Não faz
- [x] Arquitetura técnica travada
- [x] IAM model definido
- [x] Superfícies definidas (Web + Desktop + API/MCP)
- [x] Product Context System definido
- [x] Personas + Mapa de Empatia (mín. 3 personas: Core individual, Enterprise AE, Enterprise Manager)
- [x] Diagrama de Casos de Uso (entrega Agile)
- [x] Backlog priorizado MVP com User Stories
- [x] Padrões de desenvolvimento + arquivos de contexto para IA
- [ ] Dataset de transcrições (sintetizar se TOTVS não fornecer)
- [ ] Modelagem de dados Oracle (Database Design)
- [ ] Domínio Java DDD (DDD-Java)
- [ ] Notebook NLP Colab (Data Science)
- [ ] Setup repositório + estrutura de pastas de desenvolvimento

---

**Última atualização:** 2026-05-04
