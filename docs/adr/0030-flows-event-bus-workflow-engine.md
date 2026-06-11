# ADR 0030 — NORA Flows: event bus in-process pós-commit + workflow engine

- **Status:** aceito
- **Data:** 2026-06-11
- **Decisores:** Arquiteto NORA (run do pitch) + Stratfy (PO, via GOAL.md)
- **Relacionados:** ADR 0002 (multi-tenancy), ADR 0019/0028 (RLS), ADR 0014 (escopo v1 — estendido
  pelo GOAL.md do pitch), ADR 0029 (padrão de job multi-tenant sob RLS)

## Contexto

O NORA Flows (builder visual de automações estilo n8n) precisa reagir a fatos de domínio — o
âncora é "análise de reunião concluída" — e executar ações reais (e-mail, futuramente Gmail/
Calendar/Slack via OAuth). Até aqui o backend não tinha NENHUM mecanismo de eventos: os hooks
pós-análise eram chamadas diretas inline em `AnalysisService.run()` (embeddings, usage,
confidence), todas fail-soft. Acoplar o engine de workflows inline no pipeline de análise violaria
o isolamento (falha de workflow não pode atrasar nem derrubar análise) e não escala para novos
gatilhos.

Restrições: monólito Spring Boot em Azure Container Apps (sem broker provisionado), Azure for
Students (orçamento), deadline do pitch 15/06. Tenant isolation + RLS são invioláveis; listeners
rodam fora do request e NÃO herdam o `TenantContextHolder` (mesmo problema já resolvido no
`AsyncConfig.taskDecorator` e no `RetentionSweeper`).

## Decisão

1. **Event bus in-process** sobre `ApplicationEventPublisher` do Spring, atrás da porta
   `application/ports/DomainEventPublisher` (DDD: application publica via porta; o adapter
   `SpringDomainEventPublisher` vive em infrastructure).
2. **Semântica pós-commit**: com transação ativa no publish, o adapter registra um
   `TransactionSynchronization.afterCommit` (rollback descarta o evento); sem transação ativa
   (caso do `AnalysisService.run()`, que comita status em transações curtas), entrega imediata.
   Listener nunca observa estado não-commitado.
3. **Eventos de domínio** são records puros em `domain/event/` (sem Spring). Âncora:
   `MeetingAnalysisCompletedEvent(tenantId, meetingId, analysisId, occurredAt)`, emitido em
   `AnalysisService.run()` imediatamente após o commit do status COMPLETED, fail-soft (falha de
   publish loga WARN e não afeta a análise).
4. **Listener assíncrono** (`WorkflowEventListener`, infrastructure) com `@Async @EventListener`:
   re-seta o tenant no `TenantRlsContext` a partir do EVENTO (não confia no decorator do pool) e
   limpa no finally — padrão idêntico ao `runAsync`/`RetentionSweeper`.
5. **WorkflowEngine** (application): casa evento → workflows ATIVOS do tenant para o trigger
   (índice parcial `idx_workflows_tenant_trigger`), monta um `WorkflowEventContext` imutável do
   estado commitado, percorre o grafo (BFS a partir do gatilho), avalia condições
   (`ConditionEvaluator`), executa ações e grava CADA passo em
   `workflow_executions.log_json`. Falha de ação marca a execução FAILED (ramos independentes
   continuam, estilo n8n); falha de um workflow não impede os demais.
6. **Ações atrás da porta `ActionExecutor`** (`type()` + `execute(ctx, params)`): contrato exige
   que FALHA PROPAGE exceção (nunca fingir sucesso — barra de "REAL" do GOAL). Registry por tipo
   (`ActionRegistry`, Spring injeta a lista). Primeira ação: `send_email` via porta `EmailSender`
   (Resend em produção; o método novo `sendWorkflowNotification` propaga falha, ao contrário dos
   transacionais).
7. **Storage** (V023): `workflows` (definition_json JSONB = grafo do canvas; trigger_type
   denormalizado para o match) e `workflow_executions` (status RUNNING/SUCCESS/FAILED + log_json),
   ambas tenant_id NOT NULL + RLS enforced no padrão V022. Validação do grafo no save
   (`WorkflowDefinitionParser`: exatamente 1 gatilho, sem ciclos, ações/condições conhecidas,
   params obrigatórios) — erro vira 422 `WORKFLOW_INVALID_DEFINITION` com mensagem acionável.

## Alternativas rejeitadas

- **Broker externo (Service Bus / RabbitMQ / Kafka):** durabilidade e retry de verdade, mas custo,
  infra nova no Bicep e latência de aprendizado incompatíveis com o deadline; o monólito já é
  single-process — in-process cobre o caso. Trigger de upgrade: multi-réplica com workflows
  pesados, necessidade de retry durável ou DLQ.
- **Chamada direta do engine no `AnalysisService`:** mais simples, porém acopla domínio de análise
  ao Flows, sem pós-commit e sem isolamento de falha.
- **Outbox pattern:** resolve perda de evento em crash entre commit e publish, mas exige tabela +
  poller; aceito o risco no MVP (o "Testar" do canvas e o reprocess cobrem reexecução manual).

## Consequências

- Evento perdido se o processo morrer entre o commit do COMPLETED e o dispatch do listener
  (janela de ms; sem retry). Mitigação manual: POST `/workflows/{id}/test` e reprocess.
- Workflows rodam na MESMA réplica que concluiu a análise (in-process). Com scale-out, cada evento
  dispara exatamente uma vez (publisher local), sem coordenação extra.
- Novos gatilhos = novo record em `domain/event/` + emissão via porta + método no engine
  (`action_item.created`, `meeting.risk_detected`, `schedule.cron` reusando o padrão @Scheduled).
- Prova de integração: `WorkflowFlowIntegrationTest` (Testcontainers) cobre o caminho real
  upload → análise → evento → engine → e-mail capturado + histórico, mais CRUD, validação,
  condições, inativo e isolamento cross-tenant (404).
