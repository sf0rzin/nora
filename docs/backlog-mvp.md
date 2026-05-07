# Backlog MVP — NORA

> Documento de referência para a disciplina **Agile Methodology with Squad Framework**
> Sprint 1+2 · FIAP Challenge 2026 × TOTVS · Engenharia de Software 2º Ano

---

## Épicos

| ID | Épico | Tier | Descrição |
|---|---|---|---|
| **E1** | Identidade & Acesso | Core + Enterprise | Cadastro, login, recuperação de senha, convites, SSO pós-MVP, IAM/RBAC |
| **E2** | Ingestão de Reuniões | Core + Enterprise | Upload textual no MVP; áudio e captura ao vivo no roadmap |
| **E3** | Processamento IA | Core + Enterprise | Transcrição, NLP, resumo, extração de tarefas, embeddings |
| **E4** | Dashboard & Insights | Core + Enterprise | Visualização de reuniões, busca, filtros, histórico |
| **E5** | Gestão de Tarefas | Core | Tarefas extraídas, status, atribuição, exportação |
| **E6** | Integrações MCP | Core | Conexão com Claude MCP, Google Calendar, task managers |
| **E7** | Administração Enterprise | Enterprise | Configuração de tenant, contexto da empresa, gestão de usuários |
| **E8** | IAM Enterprise (estilo AWS) | Enterprise | Root user, Users, Groups e Policies (Effect/Action/Resource[/Condition]) gerenciados pelo próprio tenant. Sem hierarquia de roles fixas. |

---

## Priorização MoSCoW

> **M** = Must Have · **S** = Should Have · **C** = Could Have · **W** = Won't Have (v1)

---

## Backlog Priorizado

### E1 — Identidade & Acesso

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US01 | Como **visitante**, quero criar uma conta com e-mail e senha, para que eu possa começar a usar a NORA Core gratuitamente. | Lucas (Core) | M | 3 |
| US02 | Como **visitante**, quero receber um e-mail de verificação após o cadastro, para que minha conta seja validada com segurança. | Lucas (Core) | M | 2 |
| US03 | Como **usuário Core**, quero fazer login com e-mail e senha, para que eu acesse meu painel de reuniões. | Lucas (Core) | M | 2 |
| US04 | Como **usuário Core**, quero redefinir minha senha via link por e-mail, para que eu recupere o acesso caso esqueça a senha. | Lucas (Core) | M | 2 |
| US05 | Como **admin Enterprise**, quero que usuários do meu tenant façam login via SSO corporativo (Google/Azure AD), para que a autenticação siga as políticas de segurança da empresa. | Camila (Enterprise Admin) | W | 5 |
| US06 | Como **usuário Enterprise**, quero ser convidado ao tenant por e-mail corporativo, para que eu acesse o sistema sem precisar criar conta manualmente. | Rafael (Enterprise AE) | M | 3 |

---

### E2 — Ingestão de Reuniões

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US07 | Como **usuário Core**, quero fazer upload de um arquivo de transcrição (.txt, .vtt, .srt), para que a NORA processe minha reunião sem necessidade de gravação. | Lucas (Core) | M | 3 |
| US08 | Como **usuário Core**, quero fazer upload de um arquivo de áudio/vídeo (.mp3, .mp4), para que a NORA transcreva e analise automaticamente. | Lucas (Core) | W | 5 |
| US09 | Como **usuário Core**, quero ativar a captura ao vivo no Desktop App durante uma videochamada, para que a transcrição aconteça em tempo real sem intervenção manual. | Lucas (Core) | W | 8 |
| US10 | Como **usuário Core**, quero nomear e categorizar uma reunião ao fazer upload, para que eu encontre facilmente no histórico. | Lucas (Core) | S | 2 |

---

### E3 — Processamento IA

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US11 | Como **usuário Core**, quero que a NORA gere automaticamente um resumo objetivo da reunião após o processamento, para que eu economize tempo relendo transcrições longas. | Lucas (Core) | M | 5 |
| US12 | Como **usuário Core**, quero que a NORA identifique e liste as tarefas e decisões tomadas na reunião, para que eu não perca nenhum compromisso assumido. | Lucas (Core) | M | 5 |
| US13 | Como **usuário Core**, quero que a NORA identifique os participantes mencionados na reunião, para que as tarefas sejam atribuídas corretamente. | Lucas (Core) | S | 3 |
| US14 | Como **admin Enterprise**, quero que o processamento das reuniões considere o contexto da empresa configurado, para que os resumos e insights sejam mais precisos e relevantes para o nosso negócio. | Camila (Enterprise Admin) | M | 8 |
| US15 | Como **usuário Enterprise**, quero que o conteúdo das minhas reuniões seja indexado semanticamente, para que eu possa buscar por conceitos e não apenas palavras-chave. | Rafael (Enterprise AE) | S | 8 |

---

### E4 — Dashboard & Insights

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US16 | Como **usuário Core**, quero visualizar todas as minhas reuniões em um painel cronológico, para que eu tenha visão geral da minha semana/mês. | Lucas (Core) | M | 3 |
| US17 | Como **usuário Core**, quero acessar o detalhe de uma reunião (resumo, tarefas, participantes, transcrição completa), para que eu tenha todos os dados em um lugar. | Lucas (Core) | M | 3 |
| US18 | Como **usuário Core**, quero buscar reuniões por palavra-chave ou período, para que eu encontre rapidamente informações específicas. | Lucas (Core) | M | 3 |
| US19 | Como **usuário Enterprise**, quero ver somente as reuniões do meu departamento/escopo, para que eu não seja exposto a informações de outras áreas da empresa. | Rafael (Enterprise AE) | M | 5 |
| US20 | Como **admin Enterprise**, quero ter acesso a todas as reuniões do tenant, para que eu possa fazer auditorias e acompanhar a saúde operacional da empresa. | Camila (Enterprise Admin) | M | 3 |
| US21 | Como **usuário Core**, quero ver um painel de tendências (temas recorrentes, carga de tarefas), para que eu identifique padrões nas minhas reuniões ao longo do tempo. | Lucas (Core) | C | 8 |

---

### E5 — Gestão de Tarefas

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US22 | Como **usuário Core**, quero visualizar todas as tarefas extraídas das minhas reuniões em uma lista consolidada, para que eu gerencie meus compromissos sem precisar reler cada reunião. | Lucas (Core) | M | 3 |
| US23 | Como **usuário Core**, quero marcar uma tarefa como concluída, para que eu acompanhe meu progresso. | Lucas (Core) | M | 2 |
| US24 | Como **usuário Core**, quero editar o texto de uma tarefa extraída automaticamente, para que eu corrija erros de interpretação da IA. | Lucas (Core) | S | 2 |
| US25 | Como **usuário Core**, quero exportar as tarefas de uma reunião em formato .csv ou .md, para que eu importe no meu gerenciador de tarefas preferido. | Lucas (Core) | S | 3 |
| US26 | Como **usuário Core**, quero definir uma data limite para uma tarefa, para que eu não perca prazos importantes. | Lucas (Core) | C | 2 |

---

### E6 — Integrações MCP

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US27 | Como **usuário Core**, quero conectar a NORA ao Claude via MCP, para que eu possa conversar sobre o conteúdo das minhas reuniões usando IA conversacional. | Lucas (Core) | W | 8 |
| US28 | Como **usuário Core**, quero que as tarefas extraídas sejam sincronizadas automaticamente com meu Google Calendar, para que eu não precise criar compromissos manualmente. | Lucas (Core) | W | 8 |
| US29 | Como **usuário Core**, quero conectar a NORA a ferramentas de task management (Linear, Jira, Notion), para que as tarefas sejam criadas diretamente no meu fluxo de trabalho. | Lucas (Core) | W | 13 |

---

### E7 — Administração Enterprise

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US30 | Como **admin Enterprise**, quero configurar o contexto da minha empresa (nome, produtos, glossário, stakeholders), para que a NORA entenda o nosso negócio e gere insights mais relevantes. | Camila (Enterprise Admin) | M | 5 |
| US31 | Como **admin Enterprise**, quero visualizar um histórico de versões do contexto da empresa, para que eu saiba o que foi alterado e por quem. | Camila (Enterprise Admin) | S | 3 |
| US32 | Como **admin Enterprise**, quero configurar o domínio corporativo do tenant, para que apenas e-mails da minha empresa possam ser convidados. | Camila (Enterprise Admin) | M | 3 |
| US33 | Como **admin Enterprise**, quero visualizar métricas de uso do tenant (reuniões processadas, tarefas geradas, usuários ativos), para que eu avalie o ROI da ferramenta. | Camila (Enterprise Admin) | S | 5 |
| US34 | Como **admin Enterprise**, quero exportar um relatório consolidado de todas as reuniões do período, para que eu apresente resultados em reuniões de board. | Camila (Enterprise Admin) | S | 5 |

---

### E8 — IAM Enterprise (estilo AWS)

| ID | User Story | Persona | MoSCoW | Pontos |
|---|---|---|---|---|
| US35 | Como **Root** do tenant, quero criar grupos no meu tenant (ex.: "Vendas-SP", "Auditores"), para que eu organize usuários sem depender de roles pré-definidas pela plataforma. | Camila (Enterprise Admin) | M | 3 |
| US36 | Como **Root**, quero criar políticas em formato JSON (Effect/Action/Resource[/Condition]) e versionar cada alteração, para que eu defina exatamente o que cada grupo ou usuário pode fazer e em quais recursos. | Camila (Enterprise Admin) | M | 8 |
| US37 | Como **Root**, quero anexar e desanexar políticas a grupos e a usuários, para que eu componha permissões por agregação sem reescrever políticas. | Camila (Enterprise Admin) | M | 3 |
| US38 | Como **Root**, quero adicionar e remover usuários de grupos, para que eu ajuste o acesso conforme as pessoas mudam de função. | Camila (Enterprise Admin) | M | 2 |
| US39 | Como **usuário Enterprise**, ao tentar acessar um recurso fora das minhas permissões, quero receber uma mensagem clara (HTTP 403 com código de erro estável), para que eu entenda o limite sem confusão. | Rafael (Enterprise AE) | M | 2 |
| US40 | Como **Root**, quero visualizar um log de auditoria de mudanças de IAM (criação/alteração/anexação de políticas e grupos), para que mudanças sensíveis sejam rastreáveis. | Camila (Enterprise Admin) | M | 5 |
| US41 | Como **Root**, quero usar **templates de policy** ("ReadOnlyAccess", "MeetingAnalystAccess") como ponto de partida, para que o onboarding seja rápido sem perder a flexibilidade. | Camila (Enterprise Admin) | S | 3 |
| US42 | Como **Root**, quero um **editor visual de policy** (form-based) que gere o JSON correto, para que eu evite erros de sintaxe ao criar políticas. | Camila (Enterprise Admin) | S | 8 |
| US43 | Como **Root**, quero um **simulador de policy** que responda "esse usuário pode fazer X em Y?", para que eu valide permissões antes de aplicar. | Camila (Enterprise Admin) | S | 5 |
| US44 | Como **Root**, quero **permission boundaries** por grupo (limite máximo de permissões), para que eu garanta que sub-admins não concedam mais do que estão autorizados a delegar. | Camila (Enterprise Admin) | C | 8 |

---

## Resumo de Prioridades

| MoSCoW | Qtd. Stories | Total de Pontos |
|---|---|---|
| MoSCoW | Qtd. Stories | Total de Pontos |
|---|---|---|
| **Must Have** | 27 | 94 |
| **Should Have** | 12 | 47 |
| **Could Have** | 5 | 28 |
| **Won't Have (v1)** | 6 | 47 |
| **Total** | **50** | **216** |

> Stories `W` reagrupam o que foi alinhado com `CLAUDE.md` e `docs/PROJECT.md` como pós-MVP: SSO corporativo (US05), upload de áudio/vídeo (US08), captura ao vivo no Desktop (US09), MCP Claude (US27), MCP Calendar (US28) e MCP task managers (US29).

---

## MVP — Escopo da Versão 1.0

O MVP da NORA v1.0 contempla exclusivamente as stories classificadas como **Must Have**, distribuídas nos três fluxos centrais:

### Fluxo 1 — Usuário Core (Lucas)
1. Criar conta e fazer login
2. Fazer upload de transcrição textual
3. Receber resumo, decisões e tarefas extraídas
4. Visualizar e gerenciar tarefas extraídas
5. Buscar reuniões no histórico

### Fluxo 2 — Root do tenant Enterprise (Camila)
1. Configurar tenant com domínio corporativo
2. Convidar usuários; criar **grupos** e **políticas** estilo AWS
3. Anexar políticas a grupos/usuários; adicionar usuários a grupos
4. Configurar contexto da empresa (product context injection)
5. Visualizar todas as reuniões do tenant (Root tem bypass)
6. Auditar mudanças de IAM

### Fluxo 3 — Usuário Enterprise (Rafael)
1. Aceitar convite e fazer login com e-mail/senha corporativo
2. Visualizar apenas as reuniões permitidas pelas políticas IAM aplicáveis ao seu usuário/grupos
3. Acessar resumo e tarefas das reuniões visíveis
4. Receber mensagem clara (HTTP 403) ao tentar acessar conteúdo fora das permissões

---

## Critérios de Aceitação — Stories Críticas

### US11 — Gerar resumo da reunião

**Dado que** uma reunião foi processada com sucesso,
**quando** o usuário acessa o detalhe da reunião,
**então** deve ver um resumo em português com: objetivo da reunião, principais pontos discutidos, decisões tomadas e próximos passos.

**Regras de negócio:**
- Resumo deve ter entre 150 e 500 palavras
- Deve ser gerado em até 30 segundos após o processamento
- Deve usar o contexto da empresa (Enterprise) quando disponível

---

### US14 — Contexto da empresa no processamento

**Dado que** o admin configurou o contexto da empresa,
**quando** uma reunião do tenant é processada pela NORA AI,
**então** o resumo e as tarefas geradas devem refletir a terminologia e prioridades configuradas no contexto.

**Regras de negócio:**
- Contexto é injetado como instrução base no prompt da IA
- Atualizar o contexto não reprocessa reuniões antigas
- O contexto é isolado por tenant (não vaza entre empresas)

---

### US19 — Visibilidade escopo-restrita (Enterprise)

**Dado que** um usuário Enterprise tem políticas IAM que limitam seu acesso (ex.: condition `nora:Department = "sales"`),
**quando** ele acessa o painel de reuniões,
**então** vê apenas reuniões cujos atributos satisfazem as políticas Allow aplicáveis e não caem em políticas Deny.

**Regras de negócio:**
- Filtro aplicado no backend (não apenas no frontend)
- Tentativa de acesso direto por URL a recurso fora das permissões retorna `403`
- Root do tenant tem bypass total e vê tudo

---

### US36 — Políticas IAM (Effect/Action/Resource/Condition)

**Dado que** o Root acessa "Configurações > IAM > Políticas",
**quando** ele cria uma nova política enviando um documento JSON com `version`, `statements[]` (cada um com `effect`, `action[]`, `resource[]` e `condition` opcional),
**então** a política deve ser persistida com versão 1, validada contra o schema oficial e disponível para anexação a grupos/usuários.

**Regras de negócio:**
- Políticas são sempre escopadas ao tenant; não vazam entre tenants.
- Cada alteração cria nova versão em `iam_policy_versions` (histórico imutável).
- A avaliação segue ordem: Root → Allow; senão, **Deny** explicitíssimo vence; senão, exigir pelo menos um Allow aplicável; default Deny.
- Wildcards (`*`) são suportados em `action` e `resource`.
- Conditions usam operadores estilo AWS (`stringEquals`, `stringIn`, `dateGreaterThan`, etc.).
