# Progresso — Pitch FIAP 2026-06-15 (NORA 100% Real + NORA Flows)

> Log vivo do run pareado (Anthony + Fable 5 Ultracode). Atualize a cada fatia.
> Goal canônico: ../../GOAL.md

## Como usar
- Marque cada item do DoD quando ficar **REAL** (não só codado: verificado de verdade).
- Registre HANDOFFs abertos/pendentes para não perder contexto entre sessões.
- Anote riscos para a revisão humana antes do palco.

## Status por fase
| Fase | Item | Estado | Verificado? | Notas |
|---|---|---|---|---|
| 0 | Event bus + MeetingAnalysisCompletedEvent | ✅ codado | IT verde | ADR 0030; porta DomainEventPublisher + adapter pós-commit; emissão em AnalysisService.run() fail-soft |
| 0 | Storage + engine Flows (V023+, RLS) | ✅ codado | IT verde | V023 (workflows + workflow_executions, RLS padrão V022); WorkflowEngine BFS + ConditionEvaluator + ActionRegistry; API /workflows CRUD + test + executions |
| 0 | Ação "Enviar e-mail" real (Resend) | ✅ codado | IT verde (e-mail capturado) | EmailSender.sendWorkflowNotification PROPAGA falha; falta verificação com Resend real em produção |
| 0 | Spike OAuth Google | 🟨 backend pronto | testes verdes (Google stubado) | ADR 0031: V024 + state HMAC + AES-GCM + refresh runtime + ações gmail_send_email/calendar_create_event; FALTA handoff (projeto Google Cloud + client id/secret) p/ ficar REAL |
| 1 | Canvas /fluxos (grid + nós + Testar) | ⬜ pendente | — | |
| 1 | Cenário-âncora ao vivo | ⬜ pendente | — | |
| 2 | Gmail (OAuth real) | ⬜ pendente | — | |
| 2 | Google Calendar (OAuth real) | ⬜ pendente | — | |
| 2 | Slack (OAuth real) | ⬜ pendente | — | |
| 3 | Settings (Conta/Segurança/Workspace) salvam | ⬜ pendente | — | endpoints faltantes |
| 3 | LGPD DELETE /users/me | ⬜ pendente | — | |
| 3 | Chat sobrevive reload | ⬜ pendente | — | |
| 3 | Dashboard paginação + polling | ⬜ pendente | — | |
| 3 | Badge PII real | ⬜ pendente | — | |
| 3 | Export relatório (MD/PDF) | ⬜ pendente | — | |
| 3 | Admin saúde + métricas de negócio | ⬜ pendente | — | |
| 4 | Mais gatilhos/ações/condições | ⬜ pendente | — | |
| 4 | Templates + dry-run + polish | ⬜ pendente | — | |

## HANDOFFs humanos (abertos / resolvidos)
| Data | Pedido | Status | Resultado |
|---|---|---|---|
| — | (nenhum ainda) | — | |

## Riscos para o palco
- (preencher conforme surgirem)

## Decisões registradas (ADR)
- (linkar ADRs criados: event bus, workflow engine, OAuth/token)
