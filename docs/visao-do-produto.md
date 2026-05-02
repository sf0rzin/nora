# Visão do Produto — NORA

> Documento de referência para a disciplina **Agile Methodology with Squad Framework**
> Sprint 1+2 · FIAP Challenge 2026 × TOTVS · Engenharia de Software 2º Ano

---

## 1. Declaração da Visão

> *Formato Geoffrey Moore — padrão de mercado para visão de produto em Agile*

---

**PARA** profissionais e equipes que participam de reuniões de trabalho e precisam transformar conversas em ações concretas,

**QUE** perdem inteligência crítica gerada nessas reuniões porque o conhecimento fica preso na memória individual, em anotações desconexas ou em CRMs subpreenchidos — resultando em oportunidades perdidas, projetos desalinhados e churn não detectado,

**O NORA** é uma plataforma SaaS de inteligência conversacional com dois planos complementares — **Core** e **Enterprise** — e três superfícies integradas: web, desktop e API/MCP,

**QUE** processa automaticamente transcrições de reuniões e entrega, em segundos, resumos estruturados, action items e, para equipes comerciais, sinais avançados de oportunidade, risco e saúde de conta — com contexto calibrado ao próprio negócio do cliente, integração nativa via **Model Context Protocol (MCP)**, IAM enterprise-grade e total conformidade com a LGPD,

**DIFERENTE DE** ferramentas genéricas de transcrição (Otter.ai, Fireflies) ou plataformas de Sales Intelligence internacionais (Gong, Clari) que usam conhecimento genérico, não falam português nativamente e não respeitam a legislação brasileira de proteção de dados,

**O NORA** é a única plataforma de inteligência conversacional que aprende o vocabulário, os produtos e o contexto de cada empresa — tornando qualquer organização capaz de extrair inteligência real das suas próprias reuniões, independentemente do setor ou ecossistema. Começa como copiloto pessoal (Core, freemium) e evolui para motor de receita completo para times (Enterprise), com adoção natural de baixo para cima *(Product-Led Growth)*.

---

## 2. A Plataforma: Core e Enterprise

O NORA é uma plataforma com dois planos que compartilham o mesmo motor de IA e infraestrutura, mas atendem perfis e necessidades distintos.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PLATAFORMA  NORA                            │
├──────────────────────────────────┬──────────────────────────────────┤
│          NORA  CORE              │        NORA  ENTERPRISE          │
│      Profissional Individual     │         Equipes e Empresas       │
├──────────────────────────────────┼──────────────────────────────────┤
│ • Resumo automático de reuniões  │ • Tudo do Core +                 │
│ • Detecção de action items       │ • Product Context via RAG        │
│ • Rastreamento de projetos       │ • Account Health Score temporal  │
│ • PII Shield pessoal (LGPD)      │ • Competitive Radar configurável │
│ • Integração via MCP:            │ • Next Best Action comercial     │
│   · Google Calendar / Outlook   │ • IAM: RBAC + ABAC (departamento │
│   · Linear / Jira               │   / projeto / conta)              │
│   · GitHub                      │ • Team Analytics & dashboards    │
│ • Freemium / plano individual    │ • SSO (Entra ID / SAML 2.0 —     │
│                                  │   pós-MVP)                       │
│                                  │ • Multi-tenancy isolado (RLS)    │
│                                  │ • SLA enterprise + suporte BR    │
├──────────────────────────────────┴──────────────────────────────────┤
│  SUPERFÍCIES: Web SaaS · Desktop (Tauri, real-time) · API / MCPs  │
├─────────────────────────────────────────────────────────────────────┤
│  GO-TO-MARKET: Product-Led Growth                                   │
│  Indivíduo adota Core (freemium) → apresenta à empresa →            │
│  empresa contrata Enterprise                                        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Quadro É — Não É

> *Define o que o produto É e o que ele NUNCA deve ser confundido.*

|  | **É** | **Não É** |
|---|---|---|
| **Natureza** | Uma **plataforma de inteligência conversacional** com dois planos (Core e Enterprise) e três superfícies (Web, Desktop, API/MCP) | Um CRM, um ERP ou substituto de qualquer sistema de gestão |
| **Core** | Um **copiloto pessoal de reuniões** para o profissional individual — organiza projetos, cria tasks, registra decisões | Um aplicativo de notas, gravador genérico ou substituto do Notion/Linear |
| **Enterprise** | Um **motor de inteligência comercial** configurado com o contexto e produtos da própria empresa — para qualquer setor | Uma ferramenta exclusiva do ecossistema TOTVS ou de qualquer outro vendor específico |
| **Contexto** | Uma plataforma que **aprende o vocabulário do cliente**: cada empresa configura seus produtos, concorrentes e termos | Uma IA genérica que usa conhecimento hardcoded de um único mercado |
| **Desktop** | Um **app real-time** (Tauri/Windows) que captura e analisa a reunião enquanto acontece | Um plugin de videoconferência ou extensão de browser |
| **IAM** | Um sistema de **controle de acesso granular** (RBAC + ABAC) onde cada colaborador vê apenas o que tem autorização | Um sistema onde todos na empresa enxergam todas as transcrições de todos os departamentos |
| **IA** | Motor de **análise, estruturação e recomendação** que amplifica o humano | Uma IA que toma decisões comerciais de forma autônoma sem revisão humana |
| **Integração** | Uma plataforma **aberta via MCP**: os dados *saem* do NORA para as ferramentas que o usuário já usa | Um sistema fechado que exige substituir as ferramentas existentes |
| **Dados** | Um sistema **LGPD-first**: PII é detectado e redigido antes de qualquer LLM externo, com consentimento explícito | Uma plataforma que armazena ou compartilha dados de conversas de terceiros |
| **Modelo** | Um SaaS com **freemium individual** (Core) evoluindo para **enterprise pago** via PLG | Um produto que exige compra corporativa como ponto de entrada |

---

## 4. Quadro Faz — Não Faz

> *Define o comportamento, funcionalidades e limites do produto.*

|  | **Faz** | **Não Faz** |
|---|---|---|
| **Input** | Aceita transcrições em **texto** (MVP) e upload de **áudio** (Sprint 3+); desktop captura áudio do sistema em **tempo real** | Não se integra nativamente a plataformas de videoconferência no MVP (Teams/Meet/Zoom — roadmap) |
| **PII Shield** | **Detecta e redige automaticamente** CPF, CNPJ, valores monetários sigilosos e nomes pessoais antes de qualquer envio à IA externa | Não retém áudio original após extração e validação; quando necessário, usa armazenamento temporário com TTL curto |
| **Core — Resumo** | **Gera resumo estruturado**: contexto, decisões tomadas, próximos passos | Não reescreve ou edita criativamente o conteúdo — sempre preserva a intenção original |
| **Core — Action Items** | **Detecta e categoriza tarefas** explícitas e implícitas com nível de confiança exibido | Não garante captura de 100% dos itens — insumo para revisão humana |
| **Core — Projetos** | **Mantém rastreabilidade de projetos** ao longo do tempo sem preenchimento manual | Não é um gerenciador de projetos — envia dados para Jira/Linear via MCP |
| **Core — MCPs** | **Integra via MCP** com Calendar/Outlook, Linear/Jira, GitHub e Salesforce/HubSpot | Não requer uso de todas as integrações — cada MCP é opcional e independente |
| **Enterprise — Product Context** | **Aprende o negócio do cliente**: admin configura catálogo de produtos, concorrentes e termos; IA usa esse contexto via RAG em toda análise | Não usa conhecimento genérico ou hardcoded de nenhum vendor — o contexto é sempre do tenant |
| **Enterprise — Oportunidades** | **Identifica gatilhos de compra** com produto do próprio catálogo sugerido e probabilidade estimada | Não substitui a qualificação humana do vendedor |
| **Enterprise — Retenção** | **Sinaliza risco de churn**: insatisfação, comparações com concorrentes configurados, sinais de saída | Não envia alertas automáticos no MVP — disponível via webhooks |
| **Enterprise — Health Score** | **Calcula Account Health Score temporal**: rastreia evolução da saúde da conta em múltiplas reuniões | Não acessa dados financeiros do ERP do cliente |
| **Enterprise — Next Action** | **Recomenda Next Best Action** nas próximas 48–72h com base no padrão da conversa | Não cria automaticamente tarefas no CRM — envia via MCP ou webhook |
| **IAM — Roles** | **RBAC com quatro níveis**: Root, Admin, Manager, Analyst/Viewer | Não permite criar roles customizadas no MVP |
| **IAM — Policies** | **ABAC por atributo**: restringe acesso por departamento, projeto ou conta do cliente | Não aplica policies em tempo real no desktop sem conexão com o servidor |
| **Desktop** | **App Windows** (Tauri/Rust) que captura áudio do sistema via WASAPI, transcreve em streaming e exibe coaching ao vivo | Não suporta macOS no MVP (driver de áudio virtual exige complexidade extra) — roadmap |
| **Multi-tenancy** | **Isolamento completo por organização** via Postgres RLS — dados de um tenant invisíveis a outro | Não oferece instalação on-premises no MVP |
| **Conformidade** | **LGPD by design**: consentimento, registro auditado, direito ao esquecimento | Não realiza DPIAs automaticamente — ação manual do DPO do cliente |

---

## 5. IAM — Controle de Acesso Enterprise

O NORA Enterprise implementa um modelo híbrido **RBAC + ABAC**, inspirado no AWS IAM. Nenhum colaborador vê mais do que precisa ver.

```
Empresa (Tenant)
├── Root / Owner       — acesso irrestrito, gerencia billing e org
├── Admin              — gerencia usuários/roles/policies
├── Roles (RBAC)
│   ├── Manager        — vê dashboards e health score do time
│   ├── Analyst        — lê, comenta e exporta
│   └── Viewer         — só leitura
└── Policies (ABAC)    — filtram o escopo de cada role
    ├── department:     ["sales", "design", "product"]
    ├── project:        ["proj-alpha"]
    └── client_account: ["conta-XYZ"]
```

**Exemplo real:** Diretor de Design com role `Manager` + policy `department: design`
→ Gerencia o time de design, **nunca vê** uma transcrição de reunião de vendas.

**Efeito no Product Context:** cada departamento pode ter subcatálogo próprio de produtos e termos. A IA usa o contexto correto baseado em quem analisa.

**Efeito no Desktop:** a transcrição já nasce tagueada com `department`, `project` e `participants` desde a captura, aplicando as policies do usuário logado.

## 6. Por que MCPs mudam o jogo

O **Model Context Protocol (MCP)** é um padrão aberto que permite ao NORA conectar-se a ferramentas externas de forma segura e padronizada — sem integrações frágeis ou manutenção de webhooks ad-hoc. Para o usuário, significa que o NORA "fala" com as ferramentas que ele já usa:

```
Reunião transcrita no NORA
         │
         ├─── MCP → Google Calendar / Outlook  → Registra resumo no evento da reunião
         │
         ├─── MCP → Linear / Jira              → Cria issues com as action items detectadas
         │
         ├─── MCP → GitHub                     → Linka discussão técnica ao PR/issue mencionado
         │
         └─── MCP → Salesforce / HubSpot / TOTVS CRM → Empurra oportunidade com contexto estruturado
```

Isso elimina o principal atrito de adoção de ferramentas de produtividade: a dupla entrada de dados. O usuário transcreve uma vez — o NORA distribui para onde precisa ir.

---

## 7. Proposta de Valor por Plano

**NORA Core:**
> *"Saia de qualquer reunião sabendo exatamente o que foi decidido, o que você precisa fazer e como isso se conecta aos seus projetos — sem anotar uma única linha."*

**NORA Enterprise:**
> *"Configure o NORA com o vocabulário do seu negócio e transforme cada reunião com clientes em inteligência acionável: veja oportunidades antes do concorrente, detecte churn com semanas de antecedência e saiba exatamente qual é o próximo passo — no seu setor, com os seus produtos, na sua língua."*

---

## 8. Contexto de Mercado

**Mercado global de Sales Intelligence** (Gong, Clari, Chorus): produtos em inglês, custo > US\$100/usuário/mês, conhecimento genérico (sem vocabulário do cliente), sem conformidade LGPD nativa.

**Ferramentas de transcrição genéricas** (Otter.ai, Fireflies, Granola): capturam texto, não extraem inteligência e não integram de forma estruturada.

**NORA preenche dois vácuos simultâneos:**
1. Plataforma brasileira, LGPD-compliant, em português, com adoção PLG.
2. Única plataforma que aprende o contexto de qualquer empresa — não só as listadas em seu banco de dados fixo.

**Mercado inicial: ecossistema TOTVS** (primeira referência de cliente vertical para go-to-market).

**Impacto estimado no Enterprise (hipóteses a validar):**
- ↓ 40% no tempo de preenchimento de CRM pós-reunião.
- ↑ Detecção de churn com 30–60 dias de antecedência.
- ↑ Taxa de upsell capturado vs. identificado (hoje estimado < 20%).

---

## Histórico do Documento

| Versão | Data | Descrição |
|---|---|---|
| 0.1 | 2026-05-01 | Criação inicial — Declaração da Visão + Quadros É/Não É e Faz/Não Faz |
| 0.2 | 2026-05-01 | Expansão para plataforma com dois planos: Core + Enterprise + MCPs + PLG |
| 0.3 | 2026-05-02 | Plataforma horizontal (qualquer empresa, não só TOTVS) + Desktop real-time (Tauri) + IAM RBAC+ABAC + três superfícies + Product Context via RAG |
| 0.4 | 2026-05-02 | Alinhamento de MVP: SSO e Desktop como pós-MVP; áudio com armazenamento temporário por TTL |
