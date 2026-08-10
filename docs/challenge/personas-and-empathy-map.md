# Personas and Empathy Maps — NORA

> Reference document for the **Agile Methodology with Squad Framework** course
> Sprint 1+2 · FIAP Challenge 2026 × TOTVS · Software Engineering, 2nd Year

## About this document

The personas were built to represent the **three central user profiles** of the NORA platform, covering both plans (Core and Enterprise) and the two hierarchy levels of Enterprise (field and management). Each persona comes with an **Empathy Map** structured around the six standard quadrants of the Agile methodology.

In the MVP backlog, the official personas are: **Lucas** for individual Core usage, **Rafael** for the Enterprise field user and **Camila** for the Enterprise manager/admin profile.

> **Note about Camila:** in the MVP she accumulates **two roles** that in larger companies could be separate — commercial manager (consumer of dashboards and Account Health) and tenant admin (responsible for company context, IAM, invitations and auditing). Every story marked "Camila (Enterprise Admin)" in the backlog refers to the tenant admin role; when the story is about consuming commercial insights, the same persona appears as a manager. As the product evolves, these roles may be disambiguated into two distinct personas (e.g. "Camila — Commercial Manager" and "Tenant IT/Owner").

## Persona 1 — Lucas Mendes

### Profile

| Attribute | Detail |
|---|---|
| **Name** | Lucas Mendes |
| **Age** | 27 years old |
| **Role** | Product Manager Jr. / Full-Stack Dev |
| **Company** | Technology startup (40 employees) |
| **NORA plan** | Core (Individual / Freemium) |
| **Location** | São Paulo, SP |
| **Education** | Bachelor's degree in Computer Science |
| **Day-to-day tools** | GitHub, Linear, Notion, Google Meet, Slack, Figma |

### Context

Lucas works at a fast-growing technology startup. He splits his time between feature development and product alignment — which means attending 6 to 8 meetings a week: planning, refinement, design syncs, stakeholder meetings. He is organized by nature, but the pace of the environment does not let him take careful notes. The result: tasks get lost among Slack conversations, decisions are revisited weeks later because nobody recorded them, and Linear piles up issues created in a rush that lose their context.

Lucas discovered transcription tools (Fireflies, Otter.ai) but abandoned all of them because they deliver a wall of text with no useful structure. What he wants is not the transcript — it is knowing **what was decided, who is going to do what and when**.

### Empathy Map — Lucas Mendes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          LUCAS  MENDES, 27                              │
│                   Product Manager Jr. / Dev Full-Stack                  │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│       PENSA E SENTE          │                  VÊ                      │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Saí dessa reunião sem     │ • Colegas saindo de calls sem anotar     │
│   saber o que ficou definido │   nada e pedindo resumo no Slack depois  │
│   de verdade."               │                                          │
│                              │ • Tarefas criadas no Linear sem contexto │
│ • Sente culpa quando não     │   ("o que era mesmo isso?")              │
│   cria os issues no Linear   │                                          │
│   logo após a reunião        │ • Issues duplicados porque duas pessoas  │
│                              │   criaram a mesma tarefa sem saber       │
│ • Ansioso com a quantidade   │                                          │
│   de informação que precisa  │ • Times maiores que o dele usando Notion │
│   processar em paralelo      │   AI mas ainda reclamando de reuniões    │
│                              │                                          │
│ • Sente que perde tempo em   │ • Notificações do GitHub sobre PRs       │
│   reuniões que poderiam ser  │   mencionados em reuniões mas não        │
│   um documento               │   linkados a nenhuma issue               │
├──────────────────────────────┼──────────────────────────────────────────┤
│           OUVE               │               FALA E FAZ                 │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Você anotou o que foi     │ • Abre o Notion durante a reunião e      │
│   decidido?" (do tech lead)  │   tenta anotar em tempo real, perdendo   │
│                              │   foco na discussão                      │
│ • "Acho que a gente decidiu  │                                          │
│   outra coisa na semana      │ • Termina a reunião e para 15 min para   │
│   passada" (de colegas)      │   tentar reconstruir o que foi dito      │
│                              │                                          │
│ • Podcasts sobre produtividade│ • Cria issues "soltos" no Linear sem    │
│   e ferramentas de PM         │   descrição para não esquecer, promete  │
│                              │   completar depois (raramente completa)  │
│ • "Você usou o Fireflies?    │                                          │
│   Funciona bem?" (colegas    │ • Manda mensagem no Slack: "alguém       │
│   experimentando ferramentas)│   lembra o que decidimos sobre X?"      │
│                              │                                          │
│ • Feedback do gestor sobre   │ • Usa o GitHub Copilot no código mas     │
│   falta de documentação      │   não tem equivalente para reuniões      │
└──────────────────────────────┴──────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│            DORES             │                 GANHOS                   │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • Decisões retomadas semanas │ • Sair de toda reunião com resumo,       │
│   depois porque não foram    │   decisões e tasks já prontos no Linear  │
│   registradas                │                                          │
│                              │ • Ter rastreabilidade de projetos sem    │
│ • Dupla entrada de dados:    │   precisar preencher nada manualmente    │
│   participa da reunião E     │                                          │
│   precisa documentar tudo    │ • Issues no GitHub linkados ao contexto  │
│   depois manualmente         │   da reunião em que foram discutidos     │
│                              │                                          │
│ • Contexto perdido entre     │ • Poder buscar "o que foi decidido sobre │
│   ferramentas: o que foi     │   autenticação?" e ter a resposta com    │
│   dito na call não chega     │   data e participantes                   │
│   automaticamente ao Linear  │                                          │
│ • Ferramentas de transcrição │ • Integração transparente com as         │
│   entregam texto bruto sem   │   ferramentas que já usa — sem precisar  │
│   estrutura útil             │   aprender uma nova plataforma           │
└──────────────────────────────┴──────────────────────────────────────────┘
```

**How NORA solves it for Lucas:**
NORA Core processes the meeting transcript, detects action items and pushes them automatically to Linear via MCP. GitHub issues mentioned in the conversation are linked. The calendar receives the summary on the event. Lucas leaves the call and does not need to do anything else — the context is already distributed.

## Persona 2 — Rafael Souza

### Profile

| Attribute | Detail |
|---|---|
| **Name** | Rafael Souza |
| **Age** | 31 years old |
| **Role** | Account Executive |
| **Company** | TOTVS (or a company with an equivalent B2B vendor profile) |
| **NORA plan** | Enterprise (field user) |
| **Location** | São Paulo, SP (serves clients in SP and the interior of the state) |
| **Education** | Bachelor's degree in Business Administration |
| **Day-to-day tools** | Salesforce, Microsoft Teams, Outlook, LinkedIn Sales Navigator |

### Context

Rafael is one of the best salespeople in his region. He has 6 years at the company, a portfolio of 42 active accounts and a quarterly quota of R$ 1.8M. In a typical week he runs 10 to 14 client meetings — discovery, demos, negotiation, follow-up. The problem: each meeting generates an enormous volume of intelligence that he simply cannot capture with quality.

Rafael fills in Salesforce as best he can — sometimes at the end of the day, sometimes on Thursday before the 1:1 with his manager. The opportunities he records are the obvious ones (the client asked for a proposal). The subtle ones (the client mentioned they are evaluating a competitor, the client complained about the current product) stay only in his head — and slip away as he changes gears in the traffic on the way home.

The result: his manager has no real visibility of the portfolio. Clients about to churn are not flagged in advance. And Rafael, who is competent, looks disorganized in the CRM data.

### Empathy Map — Rafael Souza

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          RAFAEL  SOUZA, 31                              │
│                    Account Executive · Vendor B2B                       │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│       PENSA E SENTE          │                  VÊ                      │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Eu sei que essa conta está│ • CRM com campos vazios ou preenchidos   │
│   em risco mas não consigo   │   com informações genéricas ("reunião    │
│   provar isso pro meu gerente│   realizada, próximos passos: follow-up")│
│   com dados."                │                                          │
│                              │ • Colega perdendo uma conta que estava   │
│ • Frustração com o tempo     │   "indo bem" porque não viu os sinais    │
│   perdido preenchendo CRM    │   de saída a tempo                       │
│   com informações que já sabe│                                          │
│                              │ • Competidores como Senior e SAP sendo   │
│ • Orgulho quando fecha um    │   mencionados pelos clientes com cada    │
│   negócio complexo, sensação │   vez mais frequência                    │
│   de que conhece os clientes │                                          │
│   melhor do que qualquer     │ • Relatórios de pipeline que ele mesmo   │
│   ferramenta conseguiria     │   não confia porque sabe que o CRM       │
│                              │   está desatualizado                     │
│ • Medo de ser pego de        │                                          │
│   surpresa no 1:1 sem ter    │ • Outros AEs usando ferramentas como     │
│   os dados do cliente na     │   Gong mas sem conseguir usar porque     │
│   ponta da língua            │   está em inglês e não conhece o        │
│                              │   ecossistema TOTVS                      │
├──────────────────────────────┼──────────────────────────────────────────┤
│           OUVE               │               FALA E FAZ                 │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Rafael, o CRM está        │ • Preenche o Salesforce no carro após    │
│   desatualizado" (gerente)   │   a reunião, de memória, às vezes horas  │
│                              │   depois                                 │
│ • "O cliente mencionou a     │                                          │
│   Senior semana passada,     │ • Manda áudio no WhatsApp para si mesmo  │
│   você está acompanhando?"   │   logo após a reunião para não esquecer  │
│   (gerente no 1:1)           │   detalhes importantes                   │
│                              │                                          │
│ • "Não sabia que eles estavam│ • Chega na reunião de pipeline sem ter   │
│   insatisfeitos, como        │   revisado todas as notas e improvisa    │
│   perdemos essa conta?"      │   com o que lembra                       │
│   (diretor após churn)       │                                          │
│                              │ • Anota em papel durante a reunião e     │
│ • Treinamentos de vendas     │   perde o caderno                        │
│   sobre qualificação MEDDIC  │                                          │
│   e metodologia consultiva   │ • Tenta registrar menções a concorrentes │
│                              │   mas não tem onde estruturar isso       │
│ • Podcasts de vendas B2B     │   adequadamente no CRM                   │
└──────────────────────────────┴──────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│            DORES             │                 GANHOS                   │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • 2h+/dia perdidas entre     │ • CRM preenchido automaticamente após    │
│   reunião e documentação     │   cada reunião — com contexto real,      │
│   no CRM                     │   não só "reunião realizada"             │
│                              │                                          │
│ • Inteligência crítica        │ • Saber que a Conta X está em risco     │
│   (concorrentes, sinais de   │   antes que o cliente ligue cancelando   │
│   churn, orçamentos) fica    │                                          │
│   só na cabeça e se perde    │ • Histórico de todas as contas acessível │
│                              │   antes do 1:1 — sem precisar revisar    │
│ • Impossível revisar 42      │   anotações manuais                      │
│   contas com profundidade    │                                          │
│   antes de um 1:1 semanal    │ • Sinal de upsell detectado antes de     │
│                              │   a oportunidade esfriar: "cliente X     │
│ • Churn surpresa: nenhum     │   mencionou problemas com RH — abrir     │
│   sinal antecipado porque    │   conversa sobre módulo de Folha"        │
│   os dados do CRM não        │                                          │
│   refletem as conversas      │ • Recomendação de próxima ação depois    │
│                              │   de cada reunião: não precisar pensar   │
│ • Ferramentas de Sales       │   "o que faço agora com essa conta?"     │
│   Intelligence existentes    │                                          │
│   são em inglês e não        │ • Confiança: poder provar para o gerente │
│   reconhecem Protheus/RM     │   com dados que a conta X está saudável  │
│                              │   ou que a conta Y precisa de atenção    │
└──────────────────────────────┴──────────────────────────────────────────┘
```

**How NORA solves it for Rafael:**
NORA Enterprise processes the post-meeting transcript with the company's product catalog configured. It identifies that the client mentioned a competitor (Competitive Radar), detects an upsell signal with a specific suggested product, computes the variation in the account's Account Health Score and pushes everything to Salesforce via MCP along with the Next Best Action. Rafael leaves the meeting and Salesforce is already updated — with real intelligence.

## Persona 3 — Camila Torres

### Profile

| Attribute | Detail |
|---|---|
| **Name** | Camila Torres |
| **Age** | 42 years old |
| **Role** | Regional Sales Manager |
| **Company** | TOTVS (or a company with an equivalent profile) |
| **NORA plan** | Enterprise (management / consolidated view) |
| **Location** | São Paulo, SP |
| **Education** | MBA in Commercial Management |
| **Day-to-day tools** | Salesforce (reports), Microsoft Teams, PowerPoint, Excel |
| **Team under management** | 8 Account Executives, ~320 active accounts |

### Context

Camila has 15 years of commercial experience and 4 years as a regional manager. She leads a team of 8 AEs and is responsible for a portfolio of ~320 accounts, with a quarterly quota of R$ 12M. The problem she faces is not a lack of data — it is a lack of **reliable data**. The Salesforce reports show what the AEs remember to fill in, not what actually happened in the meetings.

Every week she spends hours in 1:1s with each AE trying to understand the real state of the portfolio. It is a verbal handover process — the AE says what he remembers, Camila writes down what she considers relevant, and decisions are made on that basis. When a client churns, the surprise is rarely genuine — the signals were there, in conversations that never reached her in a structured form.

Camila wants to predict problems, not react to them. She wants to know which account needs attention before it is too late. She wants her AEs to spend less time on bureaucracy and more in real meetings.

### Empathy Map — Camila Torres

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CAMILA  TORRES, 42                              │
│                    Gerente Regional de Vendas                           │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│       PENSA E SENTE          │                  VÊ                      │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Meu pipeline no Salesforce│ • Relatórios do Salesforce com campos    │
│   é um espelho torto do que  │   "próxima ação: follow-up" que não      │
│   está acontecendo de verdade│   dizem absolutamente nada               │
│   na carteira."              │                                          │
│                              │ • AEs passando 30% do tempo em          │
│ • Cansaço com o ciclo de     │   atividades administrativas (CRM,       │
│   "surpresa de churn":       │   relatórios, preparação para 1:1)       │
│   toda vez alguém diz        │   em vez de vender                       │
│   "estava indo bem"          │                                          │
│                              │ • Time desmotivado com burocracia de CRM │
│ • Responsabilidade pelo      │   que parece não agregar valor a eles    │
│   resultado de 8 pessoas que │                                          │
│   ela não consegue monitorar │ • Concorrentes sendo adotados por contas │
│   com precisão real          │   que o time achava "fidelizadas"        │
│                              │                                          │
│ • Frustração com reuniões    │ • Diretoria cobrando forecast preciso    │
│   de 1:1 que são basicamente │   que ela não consegue entregar com      │
│   sessões de improvisação    │   confiança                              │
│   com dados imprecisos       │                                          │
├──────────────────────────────┼──────────────────────────────────────────┤
│           OUVE               │               FALA E FAZ                 │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • "Como a gente não viu      │ • Passa 3h/semana em 1:1s onde o AE     │
│   isso vindo?" (diretoria    │   faz o papel de "banco de memória"      │
│   após perder conta grande)  │   da carteira dele                       │
│                              │                                          │
│ • "O CRM está desatualizado  │ • Constrói planilhas Excel paralelas ao  │
│   de novo" (própria          │   Salesforce para ter visão que confia   │
│   reclamação nos 1:1s)       │                                          │
│                              │ • Cobra os AEs por atualização de CRM,  │
│ • Benchmarks de gestores de  │   gerando atrito com o time             │
│   vendas usando ferramentas  │                                          │
│   de Sales Intelligence para │ • Pede que AEs mandem resumo por escrito │
│   coaching e forecast        │   após reuniões importantes — raramente  │
│                              │   acontece na prática                    │
│ • "A gente precisava de dados│                                          │
│   para defender o budget"    │ • Apresenta pipeline para diretoria com  │
│   (par de trabalho, outro    │   ressalvas: "isso aqui pode mudar       │
│   gerente)                   │   conforme a gente atualiza o CRM"      │
└──────────────────────────────┴──────────────────────────────────────────┘

┌──────────────────────────────┬──────────────────────────────────────────┐
│            DORES             │                 GANHOS                   │
├──────────────────────────────┼──────────────────────────────────────────┤
│ • Visibilidade de pipeline   │ • Dashboard com Account Health Score     │
│   baseada em repasse verbal, │   de todas as 320 contas — sem precisar  │
│   não em dados reais das     │   perguntar para cada AE                 │
│   conversas                  │                                          │
│                              │ • Alerta antecipado: "Conta X degradou   │
│ • Churn surpresa que poderia │   Health Score em 3 semanas — possível   │
│   ter sido evitado com 30    │   risco de churn. AE: Rafael."           │
│   dias de antecedência       │                                          │
│                              │ • 1:1s mais eficientes: ela chega        │
│ • 1:1s ineficientes onde ela │   sabendo o estado real da carteira,     │
│   é a interrogadora e o AE   │   1:1 vira coaching e estratégia         │
│   é o banco de memória       │                                          │
│                              │ • Forecast confiável para apresentar     │
│ • AEs desmotivados com       │   à diretoria, baseado em sinais reais   │
│   burocracia de CRM —        │   das conversas, não em estimativas do   │
│   reduz tempo de venda       │   vendedor                               │
│                              │                                          │
│ • Impossível identificar     │ • Identificar AEs que precisam de        │
│   padrões de comportamento   │   coaching específico: "Rafael está       │
│   que explicam quais AEs     │   perdendo oportunidades de upsell —     │
│   convertem mais e por quê   │   não está explorando sinais de RH"      │
└──────────────────────────────┴──────────────────────────────────────────┘
```

**How NORA solves it for Camila:**
Camila accesses the Enterprise dashboard with the Account Health Score of all the team's accounts, filtered by AE or by risk level. She does not depend on verbal handover — the data comes from the real transcripts. When an account's score degrades, she receives an alert with context ("the client mentioned evaluating a competitor in 2 consecutive meetings"). The weekly 1:1 goes from "what is happening?" to "how are we going to act on these 3 at-risk accounts?".

## Synthesis — Persona × Feature cross-reference

| NORA Feature | Lucas (Core) | Rafael (Enterprise) | Camila (Enterprise) |
|---|---|---|---|
| Structured meeting summary | Primary use | Fills the CRM | Context for the 1:1 |
| Action item detection | Primary use | Sales follow-ups | Visibility of commitments |
| Push via MCP (Linear/Jira) | Primary use | — | — |
| Push via MCP (Salesforce/CRM) | — | Primary use | Forecast |
| Product Context via RAG | — | Opportunities with the right product | Understands each account's language |
| Account Health Score | — | Account review before the 1:1 | **Primary use — portfolio view** |
| Competitive Radar | — | Detects a threat in real time | Market pattern across the team |
| Next Best Action | — | Primary use | Input for AE coaching |
| IAM ABAC (department) | — | Sees only the accounts in their own portfolio | Sees the whole region |
| Desktop real-time | Possible use (technical meetings) | Coaching during the call | — |

## Document History

| Version | Date | Description |
|---|---|---|
| 0.1 | 2026-05-02 | Creation — three complete personas with Empathy Maps and cross-reference table |
| 0.2 | 2026-05-02 | Alignment of the persona names with the MVP backlog |
