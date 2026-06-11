# GOAL — NORA 100% REAL para o Pitch FIAP (deadline: 2026-06-15)

> Documento-norte para um run autônomo PAREADO (Anthony + Fable 5 Ultracode 1M).
> Leia este arquivo inteiro antes de editar qualquer linha. Depois leia CLAUDE.md
> e os docs apontados nele (docs/product, docs/engineering, docs/adr).

## Norte (uma frase)
Deixar NORA Core, Painel Administrador e Documentos **100% funcionais e de
verdade** (zero botão morto, zero stub, zero "em breve") e entregar o
recurso-estrela **NORA Flows**: um construtor VISUAL de automações estilo Google
Stitch / n8n (canvas com fundo de grid + nós arrastáveis) onde o usuário liga
GATILHOS (ex.: "Reunião analisada") a AÇÕES estilo MCP (ex.: "Enviar e-mail",
"Enviar relatório", "Criar evento no Google Calendar") — com integrações
EXTERNAS REAIS (OAuth de verdade), rodando ponta a ponta.

## Definição de "REAL" (a barra desta entrega)
- Um e-mail é REAL quando chega numa caixa de entrada de verdade.
- Uma integração Google/Slack é REAL quando passa por OAuth de verdade e a ação
  acontece na conta externa de verdade (e-mail enviado, evento criado, mensagem
  postada).
- Um botão é REAL quando persiste no backend e sobrevive a um reload.
- NÃO existe "fingir que enviou", "toast de em breve" ou mock em tela de produção.
- Modo mock/stub só é permitido em testes automatizados e em dev local explícito.

## Regra de ouro
A cada commit, a branch builda e o caminho de demo funciona. Prefira UMA fatia
vertical real terminada a duas pela metade. Qualidade nível produto comercial.

================================================================================
## MODO PAREADO + PROTOCOLO DE HANDOFF HUMANO
================================================================================
Você tem um colaborador humano (Anthony) disponível ~24/7, com Claude Max 20x e
disposto a investir dinheiro em APIs/infra. Use isso: quando uma tarefa exigir um
passo que só um humano faz, **PARE e peça**, com instrução copiável e exata.
Nunca pule, nunca finja, nunca deixe stub no lugar.

Peça handoff humano para (exemplos):
- Criar/configurar projeto no **Google Cloud Console** (OAuth consent screen,
  OAuth Client ID, escopos Gmail/Calendar, redirect URIs em nora.systems).
- Completar um fluxo de **consentimento OAuth** no navegador (login Google/Slack).
- Criar app/Bot token no **Slack** (workspace, scopes, install).
- Colar um **secret** no cofre/vault (server-side) ou no .env local — nunca no repo.
- **Aprovar um gasto** (upgrade de modelo, tier pago, novo recurso Azure) — diga
  quanto e por quê antes.
- **Verificar visualmente** um resultado externo real (e-mail recebido, evento no
  calendário, mensagem no Slack) — peça print/confirmação.
- Confirmar que a **chave OpenAI está viva** (chat/análise quebram com 502 se a
  chave foi revogada — ver memória reference-rotacao-chaves-llm).

Formato do pedido de handoff: bloco "🙋 HANDOFF HUMANO" com (1) o que preciso,
(2) passos numerados exatos, (3) o que me devolver (token/URL/print), (4) o que
eu faço quando você voltar. Depois siga trabalhando no que não depende disso.

================================================================================
## SEGREDOS, CHAVES E GASTO
================================================================================
- Nenhum secret no repo. Nomes de variáveis em .env.example; valores no
  cofre/vault server-side (toolbelt do Anthony) ou .env (gitignored).
- Google OAuth Client Secret, Slack Bot Token, Resend API key, OpenAI key →
  cofre/env, nunca commitados, nunca ecoados em log.
- Redirect URIs de OAuth devem apontar para o domínio real (nora.systems / api.
  nora.systems) e/ou localhost para dev — peça ao humano para registrá-los.
- Ao precisar de gasto, declare custo estimado e peça aprovação (handoff).

================================================================================
## SEQUÊNCIA (queime o risco cedo; branch sempre verde)
================================================================================
Tudo abaixo é IN-SCOPE e precisa ficar REAL. A ordem existe para retirar risco
primeiro e manter a demo viva — não para cortar escopo.

### FASE 0 — Fundação + de-risk do mais assustador
1. **Event bus real** no backend: `ApplicationEventPublisher` emitindo eventos
   APÓS commit (TransactionSynchronization). Emitir `MeetingAnalysisCompletedEvent`
   no ponto de conclusão de `AnalysisService.run()`.
2. **Storage + engine do Flows** (migrations V023+: `workflows` +
   `workflow_executions`, com tenant_id + RLS) e `WorkflowEngine` (listener async)
   + `ActionExecutor` (porta + adapters).
3. **Ação "Enviar e-mail" REAL** via `ResendEmailSender` (já existe) — provar o
   pipeline evento→ação ponta a ponta por teste de integração, ANTES do canvas.
4. **Spike OAuth Google (com handoff humano):** criar projeto Google Cloud, OAuth
   client, redirect URIs, provar um "enviar e-mail via Gmail API" real num caminho
   mínimo. Retira o maior risco enquanto há tempo de recuperar.

### FASE 1 — Canvas + cenário-âncora ao vivo
5. Rota `/fluxos` no Core: canvas com **fundo de grid**, nós arrastáveis
   (quadradinhos: Gatilho / Condição / Ação), arestas conectando, sidebar de
   parâmetros, botões **Salvar** e **Testar** (executa e mostra log de execução).
6. **Cenário-âncora rodando ao vivo:** upload de transcrição → análise COMPLETED
   → evento dispara o fluxo → e-mail real + relatório real. Histórico de execução
   mostra sucesso com log.

### FASE 2 — Integrações MCP externas REAIS (OAuth de verdade)
7. **Gmail** (enviar e-mail pela conta Google do usuário) — OAuth real, token
   storage com refresh rotation, adapter MCP.
8. **Google Calendar** (criar evento a partir de action item / reunião) — real.
9. **Slack** (postar resumo/alerta num canal) — real.
   Cada conector: fluxo OAuth real + armazenamento seguro de token + adapter por
   trás da porta `ActionExecutor`. Hub de conectores em `integracoes/` vira real
   (status "Conectado"/"Conectar", nunca "em breve").

### FASE 3 — Fechar 100% de Core / Admin / Documentos (zero stub)
10. **Settings que salvam de verdade** + endpoints faltantes no backend:
    - `GET /auth/me`, `PATCH /users/me` (displayName) → aba Conta.
    - `POST /auth/password/change`, `POST /auth/logout-all` → aba Segurança.
    - `GET /tenant`, `PUT /tenant/name` → aba Workspace.
    - `DELETE /users/me` (LGPD, hard-delete) → Zona de perigo.
    - `POST /auth/verify-email/resend` → reenvio de verificação.
11. **Chat** sobrevive ao reload (sessionStorage mínimo ou rehidrata da sessão).
12. **Dashboard**: paginação real (prev/next) + auto-refresh enquanto PROCESSING
    (no dashboard e no detalhe da reunião).
13. **Badge de PII** com contador real (`metadata.piiRedactionsApplied`).
14. **Documentos**: tags completas (não só tags[0]); **export de relatório**
    (Markdown e PDF) real a partir da análise.
15. **Admin**: telemetria "Saúde do sistema" (latência/erro/throughput via App
    Insights) e "Métricas de negócio" (reuniões/chats/conversão) com dados reais.

### FASE 4 — Profundidade do Flows + polish
16. Mais gatilhos (`action_item.created`, `meeting.risk_detected`,
    `schedule.cron`), mais ações (criar tarefa, relatório consolidado), condições
    (Productivity Score < N, tag, priority, customerConfidence < N).
17. Templates de fluxo prontos, dry-run/simulador, e refinamentos de UX
    (stop/retry do chat, microcopy, estados vazios).

================================================================================
## NORA FLOWS — ESPECIFICAÇÃO
================================================================================

### Gatilhos (eventos de domínio emitidos pelo backend)
- `meeting.analysis_completed` (âncora — emitir em AnalysisService, pós-commit)
- `action_item.created`
- `meeting.risk_detected` (severidade alta)
- `schedule.cron` (ex.: diário 9h — reusar padrão @Scheduled)

### Condições (avaliador simples; sem condição = sempre dispara)
`Productivity Score < N`, `tag == X`, `priority == HIGH`,
`customerConfidence.score < N`.

### Ações (estilo MCP — porta `ActionExecutor` + adapters)
- **Enviar e-mail** (interno, Resend) — REAL
- **Enviar relatório** (gera resumo/relatório da reunião em MD/PDF e envia/baixa) — REAL
- **Enviar e-mail via Gmail** (conta Google do usuário, OAuth) — REAL
- **Criar evento no Google Calendar** (OAuth) — REAL
- **Postar no Slack** (OAuth/Bot token) — REAL
- **Criar tarefa** (action item) — REAL

### Canvas (UI)
Fundo de grid (Stitch/n8n), nós arrastáveis, arestas, sidebar de parâmetros,
Salvar + Testar com log. Tailwind CRU + tokens OKLCH + DM Sans (ADR 0013). Avalie
React Flow vs. canvas custom; decida e registre em ADR. Se React Flow, estilize
com os tokens NORA (sem trazer outro design system).

### Backend (DDD, respeitando camadas)
- Migrations V023+: `workflows(id, tenant_id, name, trigger_type, definition_json,
  active, created_at)` + `workflow_executions(id, workflow_id, tenant_id,
  event_type, status, log_json, created_at)` — tenant_id + RLS em ambas.
- `WorkflowEngine` (listener async) casa eventos → workflows ativos do tenant →
  avalia condições → executa ações via `ActionExecutor`.
- Tokens OAuth: tabela segura por tenant/usuário, refresh rotation.
- Endpoints: `GET/POST/PUT/DELETE /workflows`, `POST /workflows/{id}/test`,
  `GET /workflows/{id}/executions`, mais callbacks OAuth
  (`/integrations/{provider}/oauth/callback`). IAM + RLS + tenant_id.
- **Registrar ADR** do event bus + workflow engine + estratégia de OAuth/token.

================================================================================
## RESTRIÇÕES INEGOCIÁVEIS (quebrar = reverter)
================================================================================
- **Tenant isolation**: tenant_id em toda tabela nova; filtro no backend + RLS. ADR 0002.
- **PII nunca crua na LLM**: PIIShield é o último gate. ADR 0012.
- **JSON Schema strict** em qualquer saída LLM nova. ADR 0003.
- **Camadas DDD**: domain não conhece Spring/HTTP/SDK; application orquestra;
  infrastructure adapta; api é fina.
- **DM Sans única** + **Tailwind CRU (sem shadcn)** + tokens OKLCH via var(--token). ADR 0013.
- **UI em PT-BR** (idioma do projeto até o pitch).
- **Sem secrets no repo**; .env.example para nomes; valores no cofre/env.
- **Spotless**: `mvn spotless:apply` antes de cada commit de backend (CI roda
  spotless:check primeiro; GJF sozinho não corrige importOrder).
- **Core individual SEM IAM** (decisão Stratfy).
- **NÃO tocar no app desktop** (apps/desktop — colaborador separado).
- **NÃO quebrar** auth, IAM, multitenancy nem o chat existentes.

================================================================================
## ONDE MEXER (mapa real do código)
================================================================================
- Front Core:    apps/web/src/app/(app)/            (criar `fluxos/`)
- Settings:      apps/web/src/app/(app)/settings/    (Conta/Segurança/Workspace)
- Chat:          apps/web/src/app/(app)/chat/page.tsx + apps/web/src/app/api/chat/route.ts
- Dashboard:     apps/web/src/app/(app)/dashboard/page.tsx
- Conectores:    apps/web/src/app/(app)/integracoes/page.tsx  (vira hub real)
- API client:    apps/web/src/lib/api/client.ts + types.ts
- Design system: apps/web/src/styles/tokens.css + components.css
- Admin:         apps/admin/src/app/                 (telemetria/page.tsx → real)
- Backend ctrls: services/api/src/main/java/br/com/nora/api/api/controllers/
- Backend app:   services/api/src/main/java/br/com/nora/api/application/
  (AnalysisService.run() = ponto de emissão de evento)
- Reaproveitar:  infrastructure/email/ResendEmailSender.java (e-mail REAL),
  infrastructure/config/AsyncConfig.java (propagação de tenant em threads),
  application/privacy/RetentionSweeper.java (@Scheduled), domain/meeting (status).
- Migrations:    services/api/src/main/resources/db/migration/ (próxima: V023+)
- Worker:        services/nlp-worker/ (action items estruturados = combustível)

================================================================================
## LOOP DE TRABALHO
================================================================================
1. Pegue o item de maior prioridade ainda não feito (Fase 0 → 4 / DoD).
2. Implemente a menor fatia vertical REAL (back+front juntos quando preciso).
3. Verifique (comandos abaixo). Se precisar de passo humano, abra HANDOFF e siga
   no que não depende dele.
4. `mvn spotless:apply` (se backend). Commit pequeno referenciando IDs. Branch
   sempre verde.
5. Atualize docs/goals/progresso-pitch.md (o que ficou REAL, o que falta, riscos).
6. Repita. Registre decisões duráveis em ADR. Pare quando o DoD estiver cumprido
   ou não restar trabalho seguro sem handoff; entregue resumo + roteiro de demo +
   riscos para revisão humana antes do palco.

### Verificação por fatia
- Backend:   `mvn -q -pl services/api test` + `mvn spotless:apply`
- Worker:    `pytest` em services/nlp-worker
- Web/Admin: `npm run typecheck` + `npm run build`
- Infra:     `az bicep build` (se tocar infra)
- REAL:      rode o app e dispare o caminho de verdade (e-mail chega, OAuth
             completa, evento aparece) — peça verificação humana quando externo.

================================================================================
## ROTEIRO DE DEMO (15/06) — precisa rodar ao vivo
================================================================================
1. Login → Core. Mostra chat com sessões persistidas + dashboard de reuniões.
2. Settings: edita nome / troca senha / renomeia workspace — tudo salva de verdade.
3. NORA Flows: cria no canvas [Reunião analisada] → [Enviar e-mail] →
   [Criar evento no Google Calendar]. Salva.
4. Upload de uma transcrição. Análise termina. O fluxo dispara sozinho:
   e-mail real chega + evento real aparece no Google Calendar.
5. Abre histórico de execuções do fluxo: log verde, ponta a ponta.
6. Admin: mostra modelos, custos e saúde do sistema com dados reais.

================================================================================
## PROMPT DE PARTIDA (cole como 1ª mensagem do run)
================================================================================
Você é o agente de engenharia do NORA, rodando PAREADO com o Anthony (disponível
~24/7, com orçamento para APIs/infra) até o pitch (2026-06-15). Seu objetivo
completo está em GOAL.md — leia INTEIRO primeiro, junto com CLAUDE.md e os docs
apontados. Depois mapeie o estado atual antes de editar.

Princípios:
- TUDO precisa ficar REAL (ver "Definição de REAL"): zero stub, zero "em breve",
  zero envio fingido. Integrações externas via OAuth de verdade.
- Trabalhe na sequência do Goal (Fase 0 → 4), queimando risco cedo (event bus +
  OAuth primeiro), sempre deixando a branch buildável e a demo viva.
- Quando precisar de um passo humano (OAuth, Google Cloud, Slack app, colar
  secret, aprovar gasto, verificar resultado externo), ABRA UM HANDOFF HUMANO com
  instruções exatas e siga no que não depende dele. Não finja, não pule.
- Para cada fatia: menor incremento real → verificação relevante (mvn test /
  pytest / npm typecheck+build / az bicep build) → `mvn spotless:apply` se backend
  → commit pequeno referenciando IDs → atualizar docs/goals/progresso-pitch.md.
- Respeite TODAS as restrições inegociáveis (tenant isolation + RLS, PII, JSON
  Schema strict, DDD, DM Sans + Tailwind cru + OKLCH, PT-BR, sem secrets,
  spotless, Core sem IAM). NÃO toque no desktop. NÃO quebre auth/IAM/chat.
- Registre decisões duráveis em ADR (event bus, workflow engine, OAuth/token).

Pare quando o Definition of Done estiver 100% cumprido ou só restar trabalho que
dependa de handoff humano. Entregue: (1) o que ficou pronto e REAL, (2) o que
ainda precisa de você, (3) roteiro de demo passo a passo, (4) riscos para o palco.
