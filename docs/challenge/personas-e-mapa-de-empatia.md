# Personas e Mapas de Empatia — NORA

> Documento de referência para a disciplina **Agile Methodology with Squad Framework**
> Sprint 1+2 · FIAP Challenge 2026 × TOTVS · Engenharia de Software 2º Ano

---

## Sobre este documento

As personas foram construídas para representar os **três perfis centrais de usuário** da plataforma NORA, cobrindo ambos os planos (Core e Enterprise) e os dois níveis de hierarquia do Enterprise (campo e gestão). Cada persona é acompanhada de um **Mapa de Empatia** estruturado nos seis quadrantes padrão da metodologia Agile.

No backlog do MVP, as personas oficiais são: **Lucas** para o uso Core individual, **Rafael** para o usuário Enterprise de campo e **Camila** para o perfil gestor/admin do Enterprise.

> **Nota sobre a Camila:** no MVP, ela acumula **dois papéis** que em empresas maiores poderiam estar separados — gestor comercial (consumidor de dashboards e Account Health) e admin do tenant (responsável por contexto da empresa, IAM, convites e auditoria). Toda story marcada como "Camila (Enterprise Admin)" no backlog refere-se ao papel de admin do tenant; quando a story for de consumo de insights comerciais, a mesma persona aparece como gestora. Na evolução do produto, esses papéis podem ser desambiguados em duas personas distintas (ex.: "Camila — Gestora Comercial" e "TI/Owner do tenant").

---

## Persona 1 — Lucas Mendes

### Perfil

| Atributo | Detalhe |
|---|---|
| **Nome** | Lucas Mendes |
| **Idade** | 27 anos |
| **Cargo** | Product Manager Jr. / Dev Full-Stack |
| **Empresa** | Startup de tecnologia (40 funcionários) |
| **Plano NORA** | Core (Individual / Freemium) |
| **Localização** | São Paulo, SP |
| **Escolaridade** | Bacharelado em Ciência da Computação |
| **Ferramentas do dia a dia** | GitHub, Linear, Notion, Google Meet, Slack, Figma |

### Contexto

Lucas trabalha em uma startup de tecnologia que cresce rápido. Divide o tempo entre desenvolvimento de features e alinhamento de produto — o que significa participar de 6 a 8 reuniões por semana: planning, refinement, sync com design, reuniões com stakeholders. Ele é organizado por natureza, mas a velocidade do ambiente não permite que ele anote tudo com cuidado. O resultado: tarefas ficam perdidas entre conversas do Slack, decisões são retomadas semanas depois porque ninguém registrou, e o Linear acumula issues criados às pressas que perdem contexto.

Lucas descobriu ferramentas de transcrição (Fireflies, Otter.ai) mas abandonou todas elas porque entregam um muro de texto sem estrutura útil. O que ele quer não é a transcrição — é saber **o que foi decidido, quem vai fazer o quê e quando**.

---

### Mapa de Empatia — Lucas Mendes

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

**Como o NORA resolve para Lucas:**
O NORA Core processa a transcrição da reunião, detecta action items e os empurra automaticamente para o Linear via MCP. Issues no GitHub mencionados na conversa são linkados. O calendário recebe o resumo no evento. Lucas sai da call e não precisa fazer mais nada — o contexto já está distribuído.

---

## Persona 2 — Rafael Souza

### Perfil

| Atributo | Detalhe |
|---|---|
| **Nome** | Rafael Souza |
| **Idade** | 31 anos |
| **Cargo** | Account Executive (Executivo de Contas) |
| **Empresa** | TOTVS (ou empresa com perfil equivalente de vendor B2B) |
| **Plano NORA** | Enterprise (usuário de campo) |
| **Localização** | São Paulo, SP (atende clientes em SP e interior) |
| **Escolaridade** | Bacharelado em Administração |
| **Ferramentas do dia a dia** | Salesforce, Microsoft Teams, Outlook, LinkedIn Sales Navigator |

### Contexto

Rafael é um dos melhores vendedores da sua regional. Tem 6 anos de empresa, carteira de 42 contas ativas e meta trimestral de R$ 1,8M. Em uma semana típica, conduz de 10 a 14 reuniões com clientes — descoberta, demos, negociação, follow-up. O problema: cada reunião gera um volume enorme de inteligência que ele simplesmente não consegue capturar com qualidade.

Rafael preenche o Salesforce como consegue — às vezes no fim do dia, às vezes na quinta-feira antes do 1:1 com o gerente. As oportunidades que anota são as óbvias (cliente pediu proposta). As sutis (cliente mencionou que está avaliando um concorrente, cliente reclamou do produto atual) ficam só na cabeça — e saem quando ele troca de carro no trânsito de volta para casa.

O resultado: seu gerente não tem visibilidade real da carteira. Clientes que estavam prestes a churnar não são sinalizados com antecedência. E Rafael, que é competente, parece desorganizado nos dados do CRM.

---

### Mapa de Empatia — Rafael Souza

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

**Como o NORA resolve para Rafael:**
O NORA Enterprise processa a transcrição pós-reunião com o catálogo de produtos da empresa configurado. Identifica que o cliente mencionou um concorrente (Competitive Radar), detecta sinal de upsell com produto específico sugerido, calcula variação no Account Health Score da conta e empurra tudo para o Salesforce via MCP com a Next Best Action. Rafael sai da reunião e o Salesforce já está atualizado — com inteligência real.

---

## Persona 3 — Camila Torres

### Perfil

| Atributo | Detalhe |
|---|---|
| **Nome** | Camila Torres |
| **Idade** | 42 anos |
| **Cargo** | Gerente Regional de Vendas |
| **Empresa** | TOTVS (ou empresa com perfil equivalente) |
| **Plano NORA** | Enterprise (gestão / visão consolidada) |
| **Localização** | São Paulo, SP |
| **Escolaridade** | MBA em Gestão Comercial |
| **Ferramentas do dia a dia** | Salesforce (relatórios), Microsoft Teams, PowerPoint, Excel |
| **Time sob gestão** | 8 Account Executives, ~320 contas ativas |

### Contexto

Camila tem 15 anos de experiência comercial e 4 anos como gerente regional. Lidera um time de 8 AEs e é responsável por uma carteira de ~320 contas, com meta trimestral de R$ 12M. O problema que ela enfrenta não é falta de dados — é falta de **dados confiáveis**. Os relatórios do Salesforce mostram o que os AEs lembram de preencher, não o que realmente aconteceu nas reuniões.

Toda semana ela passa horas em 1:1s com cada AE tentando entender o real estado da carteira. É um processo de repasse verbal — o AE fala o que lembra, Camila anota o que considera relevante, e as decisões são tomadas com base nisso. Quando um cliente dá churn, a surpresa raramente é genuína — os sinais estavam lá, em conversas que nunca chegaram estruturadas até ela.

Camila quer prever problemas, não reagir a eles. Quer saber qual conta precisa de atenção antes que seja tarde. Quer que seus AEs passem menos tempo em burocracia e mais em reuniões de verdade.

---

### Mapa de Empatia — Camila Torres

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

**Como o NORA resolve para Camila:**
Camila acessa o dashboard Enterprise com Account Health Score de todas as contas do time, filtrado por AE ou por grau de risco. Não depende de repasse — os dados vêm das transcrições reais. Quando o score de uma conta degrada, ela recebe alerta com contexto ("cliente mencionou avaliação de concorrente em 2 reuniões consecutivas"). O 1:1 semanal passa de "o que está acontecendo?" para "como vamos agir nessas 3 contas em risco?".

---

## Síntese — Cruzamento Persona × Feature

| Feature NORA | Lucas (Core) | Rafael (Enterprise) | Camila (Enterprise) |
|---|---|---|---|
| Resumo estruturado da reunião | ✅ Principal uso | ✅ Preenche CRM | ✅ Contexto para 1:1 |
| Detecção de action items | ✅ Principal uso | ✅ Follow-ups de venda | ✅ Visibilidade de compromissos |
| Push via MCP (Linear/Jira) | ✅ Principal uso | — | — |
| Push via MCP (Salesforce/CRM) | — | ✅ Principal uso | ✅ Forecast |
| Product Context via RAG | — | ✅ Oportunidades com produto certo | ✅ Entende linguagem de cada conta |
| Account Health Score | — | ✅ Revisão de conta pré-1:1 | ✅ **Principal uso — visão da carteira** |
| Competitive Radar | — | ✅ Detecta ameaça em tempo real | ✅ Padrão de mercado no time |
| Next Best Action | — | ✅ Principal uso | ✅ Input para coaching de AEs |
| IAM ABAC (departamento) | — | ✅ Vê só contas da própria carteira | ✅ Vê toda a regional |
| Desktop real-time | Possível uso (reuniões técnicas) | ✅ Coaching durante a call | — |

---

## Histórico do Documento

| Versão | Data | Descrição |
|---|---|---|
| 0.1 | 2026-05-02 | Criação — três personas completas com Mapas de Empatia e tabela de cruzamento |
| 0.2 | 2026-05-02 | Alinhamento dos nomes das personas com o backlog MVP |
