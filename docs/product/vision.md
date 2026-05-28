# Visão do Produto — NORA

> Documento de referência de produto. Fonte de verdade pro **O que** NORA é, pra **quem**, e o **porquê**.
>
> Para a história da disciplina **Agile Methodology with Squad Framework** (Sprint 1+2, FIAP Challenge 2026 × TOTVS) este doc serve como Vision Statement principal.

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
│ • Productivity Score opt-in      │ • Customer Confidence (por call) │
│ • Rastreamento de projetos       │ • Account Health Score temporal  │
│ • PII Shield pessoal (LGPD)      │ • Competitive Radar configurável │
│ • Integração via MCP:            │ • Next Best Action comercial     │
│   · Google Calendar / Outlook    │ • IAM estilo AWS: Root + Users + │
│   · Linear / Jira                │   Groups + Policies              │
│   · GitHub                       │ • Team Analytics & dashboards    │
│ • Freemium / plano individual    │ • SSO (Entra ID / SAML 2.0 —     │
│                                  │   pós-MVP)                       │
│                                  │ • Multi-tenancy isolado          │
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

## 3. Estado Atual (2026-05-21)

O NORA não está mais em fase de scaffolding nem de Sprint 1+2 puro de documentação. **Está deployado em Azure** e operacional ponta-a-ponta nos fluxos centrais do MVP:

- Web em produção dev: <https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io>
- 14 recursos Azure provisionados no `rg-nora-dev` (centralus): Container Apps Env, 3 Container Apps (web + api + worker), Postgres Flexible, Key Vault, Storage Account, App Insights, Log Analytics, Azure Speech, 3 User-Assigned Identities (api/worker/web), e federated credentials no SP `sp-nora-github-deploy`
- Pipeline `build-images.yml` publicando 3 imagens reais no GHCR (`ghcr.io/sys0xff/nora-{api,worker,web}`); deploy via `deploy-infra.yml` com OIDC
- IAM AWS-style operacional: Users + Groups + Policies + audit log com versionamento imutável de policies. PolicyEvaluator avalia `StringEquals` em produção; expansão para `StringIn`/`StringLike`/`DateGreaterThan` planejada pra Sub-fase 1.11
- Productivity Score full-stack (ADR 0005): backend Spring + worker NLP + web 3 componentes (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`)
- PII Shield expandido: além de email/CPF/CNPJ/phone/credit card, cobre **PERSON_NAME (BR)** com lista de ~270 nomes brasileiros + negative list (ADR 0012)
- Pipeline LLM agnóstico (ADR 0004): default OpenAI `gpt-4o-mini`, schema strict via `response_format=json_schema`
- Cobertura: worker NLP 87% (54 testes), backend Spring 67% (174 testes), web Next.js 0% (sem runner — débito pra 1.12)
- **App Core chat-first (2026-05-28)**: a superfície web do Core virou conversacional — **Chat IA com streaming** (OpenAI via ADR 0004, chave server-side num BFF `/api/chat`, contexto do workspace injetado), com Início (inbox), detalhe da reunião, Action items, Projetos e Integrações (MCP). IAM/contexto de tenant (Enterprise) saíram do nav do Core.
- **Transcrições TOTVS**: pipeline de Data Science em `notebooks/totvs_transcricoes_eda.py` processa o export real (parser dedicado do formato malformado + limpeza do `[LOCUTOR N]` + TF-IDF reusando o `nlp_baseline` + correlação linguagem×NPS para sinais de risco/oportunidade)

21 ADRs (0001–0021; ADRs 0013 e 0016 ainda em estado *Proposto* aguardando refino de design / Sub-fase 1.12) documentam as decisões duráveis. **Customer Confidence foi implementado full-stack** em **PR #148 (2026-05-21)** via ADR 0015: migration V017 (5 tabelas), worker emite o bloco `customerConfidence`, backend persiste no pipeline com trend autoritativo por conta, `GET /meetings/{id}` retorna o bloco e o `CustomerConfidenceCard` aparece no detalhe da reunião — resolvendo a dívida narrativa da landing. Account Health **agregado** (US50-51) segue deferido (ADR 0014). Já uma onda de hardening pós-1.10 (#114–#138) entregou RLS (V016), soft-delete (V013), refresh-token rotation (V014) e FK composta de isolamento (V015) — documentados em ADR 0019/0020/0021.

Pra entender o estado anterior (Sprint 1+2 documentação) consulte o histórico do documento no fim deste arquivo e o `docs/product/roadmap.md`.

---

## 4. Quadro É — Não É

> *Define o que o produto É e o que ele NUNCA deve ser confundido.*

|  | **É** | **Não É** |
|---|---|---|
| **Natureza** | Uma **plataforma de inteligência conversacional** com dois planos (Core e Enterprise) e três superfícies (Web, Desktop, API/MCP) | Um CRM, um ERP ou substituto de qualquer sistema de gestão |
| **Core** | Um **copiloto pessoal de reuniões** para o profissional individual — organiza projetos, cria tasks, registra decisões | Um aplicativo de notas, gravador genérico ou substituto do Notion/Linear |
| **Enterprise** | Um **motor de inteligência comercial** configurado com o contexto e produtos da própria empresa — para qualquer setor | Uma ferramenta exclusiva do ecossistema TOTVS ou de qualquer outro vendor específico |
| **Contexto** | Uma plataforma que **aprende o vocabulário do cliente**: cada empresa configura seus produtos, concorrentes e termos | Uma IA genérica que usa conhecimento hardcoded de um único mercado |
| **Desktop** | Um **app real-time** (Tauri 2 / Rust + sidecar Python) que captura e analisa a reunião enquanto acontece, multi-plataforma | Um plugin de videoconferência ou extensão de browser |
| **IAM** | Um sistema de **controle de acesso granular** estilo **AWS IAM** (Root + Users + Groups + Policies criadas pelo próprio tenant) | Um sistema onde todos na empresa enxergam todas as transcrições de todos os departamentos |
| **IA** | Motor de **análise, estruturação e recomendação** que amplifica o humano. Saída via JSON Schema strict | Uma IA que toma decisões comerciais de forma autônoma sem revisão humana |
| **Integração** | Uma plataforma **aberta via MCP**: os dados *saem* do NORA para as ferramentas que o usuário já usa | Um sistema fechado que exige substituir as ferramentas existentes |
| **Dados** | Um sistema **LGPD-first**: PII é detectado e redigido antes de qualquer LLM externo, com consentimento explícito | Uma plataforma que armazena ou compartilha dados de conversas de terceiros |
| **Modelo** | Um SaaS com **freemium individual** (Core) evoluindo para **enterprise pago** via PLG | Um produto que exige compra corporativa como ponto de entrada |

---

## 5. Quadro Faz — Não Faz

> *Define o comportamento, funcionalidades e limites do produto. Atualizado 2026-05-14 — reflete realidade pós deploy real Azure (Sub-fase 1.9).*

|  | **Faz** | **Não Faz** |
|---|---|---|
| **Input** | Aceita transcrições em **texto** (`.txt`, `.vtt`, `.srt`) — MVP. Desktop captura áudio do sistema em **tempo real** (Windows via WASAPI; macOS via BlackHole; Linux via PulseAudio) | Não aceita upload de áudio/vídeo arquivado no MVP (`.mp3`, `.mp4`) — está em US08 (Won't Have v1). Roadmap: pós-MVP via Azure Speech batch |
| **PII Shield** | **Detecta e redige automaticamente** CPF, CNPJ, valores monetários sigilosos, email, telefone, cartão de crédito e **nomes pessoais brasileiros (PERSON_NAME)** antes de qualquer envio à IA externa, com placeholders `[[TIPO_N]]` (ADR 0012) | Não cobre PII de endereço (ADDRESS) no MVP — débito catalogado pra pós-MVP. Não retém áudio original após extração; quando necessário, usa armazenamento temporário com TTL curto |
| **Core — Resumo** | **Gera resumo estruturado**: contexto, decisões tomadas, próximos passos, em até 30 segundos | Não reescreve ou edita criativamente o conteúdo — sempre preserva a intenção original |
| **Core — Action Items** | **Detecta e categoriza tarefas** explícitas e implícitas com nível de confiança exibido, prioridade `LOW`/`MEDIUM`/`HIGH` e citação textual da fonte (`sourceQuote`) | Não garante captura de 100% dos itens — insumo para revisão humana. Não atribui automaticamente sem nome explícito |
| **Core — Productivity Score** | **Opt-in por reunião**: usuário declara objetivo + outcomes esperados; NORA mede cobertura (`ADDRESSED`/`PARTIAL`/`MISSED`) e atribui score 0–100 com banda `LOW`/`MEDIUM`/`HIGH` (ADR 0005) | Não calcula sem opt-in — privacidade by design. Não usa benchmarks externos — só o gabarito declarado pelo próprio usuário |
| **Core — Projetos** | **Mantém rastreabilidade de projetos** ao longo do tempo sem preenchimento manual; usa attributes do tenant ou tags da reunião | Não é um gerenciador de projetos — envia dados para Jira/Linear via MCP (pós-MVP) |
| **Core — MCPs** | **Integra via MCP** com Calendar/Outlook, Linear/Jira, GitHub e Salesforce/HubSpot — todas pós-MVP | Não requer uso de todas as integrações — cada MCP é opcional e independente |
| **Enterprise — Product Context** | **Aprende o negócio do cliente**: admin configura catálogo de produtos, concorrentes, glossário e stakeholders; IA usa esse contexto via RAG/injection em toda análise | Não usa conhecimento genérico ou hardcoded de nenhum vendor — o contexto é sempre do tenant. RAG full com Azure AI Search está pós-MVP (US15) |
| **Enterprise — Customer Confidence** | **Implementado full-stack** (ADR 0015, PR #148): worker emite score 0–100 + banda + tendência + sinais de compra + objeções + `accountName`; backend persiste (V017) com trend autoritativo por conta; `GET /meetings/{id}` retorna o bloco; UI `CustomerConfidenceCard` no detalhe da reunião | Account Health **agregado** (score temporal por conta + alertas, US50-51) segue deferido (ADR 0014) — exige volume de pilot |
| **Enterprise — Account Health** | Schema previsto (ADR 0006): bandas `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`, agregado por conta, com tendência | **Não implementado** — adiado via ADR 0014 (defer post-MVP commercial gate) |
| **Enterprise — Next Action** | **Recomenda Next Best Action** nas próximas 48–72h com base no padrão da conversa | Não cria automaticamente tarefas no CRM — envia via MCP ou webhook (pós-MVP) |
| **IAM — Modelo** | **IAM granular estilo AWS**: Root + Users + Groups + Policies (Effect/Action/Resource/Condition) criados pelo próprio tenant. Versionamento imutável de policies + audit log. (ADR 0007) | Não impõe hierarquia de roles fixas (sem Manager/Analyst/Viewer pré-definidos) |
| **IAM — Conditions** | **Conditions estilo AWS** por atributos definidos pelo tenant: `Department`, `Project`, `Account` etc. PolicyEvaluator suporta `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` | Operadores fora dessa lista (e atributos ausentes no contexto) resultam em `Deny` (fail-closed) |
| **Desktop** | **App Tauri 2** (Rust + sidecar Python) com captura de áudio do sistema. **Windows via WASAPI** (oficial v1) · **macOS via BlackHole** (driver de áudio virtual; ScreenCaptureKit nativo está em débito como nice-to-have) · **Linux via PulseAudio** | Não é plugin de videoconferência. Não roda em mobile no MVP |
| **Multi-tenancy** | **Isolamento por organização** via `tenant_id` em todas as tabelas + filtro de aplicação (ADR 0002), com **RLS Postgres (V016)** e **FK composta de isolamento (V015)** como defesa em profundidade. Bicep IaC reprodutível | RLS tem enforcement **opt-in** — ativar em prod (role `nora_app` + flag) é o que falta. Não oferece instalação on-premises no MVP |
| **Conformidade** | **LGPD by design**: consentimento, registro auditado, direito ao esquecimento (modelo) | Não realiza DPIAs automaticamente — ação manual do DPO do cliente. Tabela `audit_events` global é débito pra 1.12+ |

---

## 6. IAM — Controle de Acesso Enterprise

O NORA Enterprise implementa um modelo **estilo AWS IAM**: o tenant cria seus próprios **grupos** e suas próprias **políticas**. Nenhuma role hierárquica é imposta pelo produto. Ver ADR 0007.

```
Empresa (Tenant)
├── Root user           — owner do tenant; bypass total; não removível
├── Users               — convidados pelo Root ou por quem tiver permissão de IAM
├── Groups              — criação livre ("Vendas-SP", "Auditores", etc.)
│   └── ⇄ Policies
├── Users ⇄ Groups       (N:N)
├── Users ⇄ Policies     (N:N)
└── Policies            — documento JSON: Effect / Action / Resource [/ Condition]
```

**Exemplo real:** o admin do tenant cria um grupo "Diretoria de Design" e anexa uma política que permite `meeting:read` e `analysis:read` apenas em recursos com a condição `nora:Department = "design"`.
→ Os membros do grupo gerenciam reuniões do design e **nunca veem** transcrições de vendas.

**Efeito no Product Context:** cada departamento pode ter subcatálogo próprio. A IA escolhe o subcatálogo correto com base nas conditions aplicáveis ao usuário que disparou a análise.

**Efeito no Desktop:** a transcrição nasce tagueada com os atributos relevantes (`Department`, `Project`, `Participants`) desde a captura, aplicando as policies do usuário logado.

**Ordem de avaliação:** Root bypass → Deny explícito → Allow aplicável → Default Deny.

---

## 7. Por que MCPs mudam o jogo

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

> No MVP atual nenhum servidor MCP está implementado (pastas `mcp/calendar`, `mcp/tasks`, `mcp/crm` vazias). Roadmap: pós-MVP comercial.

---

## 8. Proposta de Valor por Plano

**NORA Core:**
> *"Saia de qualquer reunião sabendo exatamente o que foi decidido, o que você precisa fazer e como isso se conecta aos seus projetos — sem anotar uma única linha."*

**NORA Enterprise:**
> *"Configure o NORA com o vocabulário do seu negócio e transforme cada reunião com clientes em inteligência acionável: veja oportunidades antes do concorrente, detecte churn com semanas de antecedência e saiba exatamente qual é o próximo passo — no seu setor, com os seus produtos, na sua língua."*

---

## 9. Personas

Três personas referenciais guiam decisões de produto:

- **Lucas** — Profissional individual, usuário Core. Gerencia múltiplos projetos, perde tempo voltando em reuniões pra lembrar do que foi decidido
- **Camila** — Admin Enterprise. Configura tenant, define quem vê o quê via IAM, configura o contexto da empresa
- **Rafael** — AE Enterprise. Vendedor que precisa ler sinais de oportunidade e risco em cada conversa com cliente

Detalhes completos, mapas de empatia, dores e ganhos: `docs/challenge/personas-e-mapa-de-empatia.md`.

---

## 10. Contexto de Mercado

**Mercado global de Sales Intelligence** (Gong, Clari, Chorus): produtos em inglês, custo > US$100/usuário/mês, conhecimento genérico (sem vocabulário do cliente), sem conformidade LGPD nativa.

**Ferramentas de transcrição genéricas** (Otter.ai, Fireflies, Granola): capturam texto, não extraem inteligência e não integram de forma estruturada.

**NORA preenche dois vácuos simultâneos:**
1. Plataforma brasileira, LGPD-compliant, em português, com adoção PLG.
2. Única plataforma que aprende o contexto de qualquer empresa — não só as listadas em seu banco de dados fixo.

**Mercado inicial: ecossistema TOTVS** (primeira referência de cliente vertical para go-to-market — pitch FIAP 12/06/2026).

**Impacto estimado no Enterprise (hipóteses a validar):**
- ↓ 40% no tempo de preenchimento de CRM pós-reunião.
- ↑ Detecção de churn com 30–60 dias de antecedência.
- ↑ Taxa de upsell capturado vs. identificado (hoje estimado < 20%).

---

## 11. Próximas Sub-fases

Detalhes em `docs/product/roadmap.md`. Resumo:

- **1.11 — Demo Polish Plano A** (em curso): ✅ Customer Confidence (#148), ✅ AUTH_FILTER_HARD_CAP fix (teto silencioso removido) e ✅ expansão `PolicyEvaluator` (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`) entregues; restam UX interna polida + dataset sintético + roteiro de demo
- **1.12 — Production Hardening**: RG dedicado de produção (`rg-nora-prod`) + RLS Postgres + monitoring alerts + LGPD operacional + DR runbook + secrets rotation + test coverage targets (ADR 0018 a criar)
- **1.13+** — Pós-pitch TOTVS (12/06+): depende do desfecho do Plano A. Cenários: dossier de pitch / due-diligence (Plano A) · Plano C content + Plano B pivô comercial

---

## Histórico do Documento

| Versão | Data | Descrição |
|---|---|---|
| 0.1 | 2026-05-01 | Criação inicial — Declaração da Visão + Quadros É/Não É e Faz/Não Faz |
| 0.2 | 2026-05-01 | Expansão para plataforma com dois planos: Core + Enterprise + MCPs + PLG |
| 0.3 | 2026-05-02 | Plataforma horizontal (qualquer empresa, não só TOTVS) + Desktop real-time (Tauri) + IAM RBAC+ABAC + três superfícies + Product Context via RAG |
| 0.4 | 2026-05-02 | Alinhamento de MVP: SSO e Desktop como pós-MVP; áudio com armazenamento temporário por TTL |
| **1.0** | **2026-05-14** | **Reescrita pós-deploy Azure real (Sub-fase 1.9). Corrige drift: Desktop suporta macOS via BlackHole (PR #37 — não é mais "Não suporta macOS no MVP"). Adiciona seção "Estado Atual" com endpoints reais + IAM operacional + Productivity Score full-stack + cobertura de testes. Adiciona seção "Próximas Sub-fases" com link pro roadmap. Substitui o doc anterior `docs/visao-do-produto.md` (deslocado pra `docs/product/vision.md`).** |
