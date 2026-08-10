# ADR 0030 — NORA Flows: in-process post-commit event bus + workflow engine

- **Status:** accepted
- **Date:** 2026-06-11
- **Related:** ADR 0002 (multi-tenancy), ADR 0019/0028 (RLS), ADR 0014 (v1 scope — extended
  by the pitch's GOAL.md), ADR 0029 (multi-tenant job pattern under RLS)

## Context

NORA Flows (a visual automation builder in the style of n8n) needs to react to domain facts — the
anchor is "meeting analysis completed" — and execute real actions (email, later Gmail/
Calendar/Slack via OAuth). Until now the backend had NO event mechanism at all: the post-analysis
hooks were direct inline calls in `AnalysisService.run()` (embeddings, usage,
confidence), all fail-soft. Coupling the workflow engine inline into the analysis pipeline would violate
isolation (a workflow failure must not delay or take down the analysis) and does not scale to new
triggers.

Constraints: a Spring Boot monolith on Azure Container Apps (no provisioned broker), Azure for
Students (budget), pitch deadline 15/06. Tenant isolation + RLS are inviolable; listeners
run outside the request and do NOT inherit the `TenantContextHolder` (the same problem already solved in
`AsyncConfig.taskDecorator` and in `RetentionSweeper`).

## Decision

1. **In-process event bus** on top of Spring's `ApplicationEventPublisher`, behind the port
   `application/ports/DomainEventPublisher` (DDD: the application publishes via a port; the adapter
   `SpringDomainEventPublisher` lives in infrastructure).
2. **Post-commit semantics**: with an active transaction at publish time, the adapter registers a
   `TransactionSynchronization.afterCommit` (a rollback discards the event); with no active transaction
   (the case of `AnalysisService.run()`, which commits status in short transactions), immediate delivery.
   A listener never observes uncommitted state.
3. **Domain events** are pure records in `domain/event/` (no Spring). Anchor:
   `MeetingAnalysisCompletedEvent(tenantId, meetingId, analysisId, occurredAt)`, emitted in
   `AnalysisService.run()` immediately after the COMPLETED status commit, fail-soft (a publish
   failure logs a WARN and does not affect the analysis).
4. **Asynchronous listener** (`WorkflowEventListener`, infrastructure) with `@Async @EventListener`:
   it re-sets the tenant in `TenantRlsContext` from the EVENT (it does not trust the pool's decorator) and
   clears it in the finally — a pattern identical to `runAsync`/`RetentionSweeper`.
5. **WorkflowEngine** (application): matches event → the tenant's ACTIVE workflows for the trigger
   (partial index `idx_workflows_tenant_trigger`), builds an immutable `WorkflowEventContext` from the
   committed state, walks the graph (BFS from the trigger), evaluates conditions
   (`ConditionEvaluator`), executes actions and records EVERY step in
   `workflow_executions.log_json`. An action failure marks the execution FAILED (independent branches
   continue, n8n style); a failure in one workflow does not prevent the others.
6. **Actions behind the `ActionExecutor` port** (`type()` + `execute(ctx, params)`): the contract requires
   that a FAILURE PROPAGATE an exception (never fake success — the GOAL's "REAL" bar). Registry by type
   (`ActionRegistry`, Spring injects the list). First action: `send_email` via the `EmailSender` port
   (Resend in production; the new `sendWorkflowNotification` method propagates failure, unlike the
   transactional ones).
7. **Storage** (V023): `workflows` (definition_json JSONB = the canvas graph; trigger_type
   denormalized for the match) and `workflow_executions` (status RUNNING/SUCCESS/FAILED + log_json),
   both tenant_id NOT NULL + RLS enforced in the V022 pattern. Graph validation on save
   (`WorkflowDefinitionParser`: exactly 1 trigger, no cycles, known actions/conditions,
   required params) — an error becomes a 422 `WORKFLOW_INVALID_DEFINITION` with an actionable message.

## Rejected alternatives

- **External broker (Service Bus / RabbitMQ / Kafka):** real durability and retry, but cost,
  new infra in Bicep and a learning latency incompatible with the deadline; the monolith is already
  single-process — in-process covers the case. Upgrade trigger: multi-replica with heavy workflows,
  a need for durable retry or a DLQ.
- **Calling the engine directly from `AnalysisService`:** simpler, but it couples the analysis domain
  to Flows, with no post-commit and no failure isolation.
- **Outbox pattern:** solves event loss on a crash between commit and publish, but requires a table +
  a poller; the risk is accepted in the MVP (the canvas's "Testar" and reprocess cover manual re-execution).

## Consequences

- An event is lost if the process dies between the COMPLETED commit and the listener dispatch
  (a window of ms; no retry). Manual mitigation: POST `/workflows/{id}/test` and reprocess.
- Workflows run on the SAME replica that completed the analysis (in-process). With scale-out, each event
  fires exactly once (local publisher), with no extra coordination.
- New triggers = a new record in `domain/event/` + emission via the port + a method in the engine
  (`action_item.created`, `meeting.risk_detected`, `schedule.cron` reusing the @Scheduled pattern).
- Integration proof: `WorkflowFlowIntegrationTest` (Testcontainers) covers the real path
  upload → analysis → event → engine → captured email + history, plus CRUD, validation,
  conditions, inactive and cross-tenant isolation (404).
