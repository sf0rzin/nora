# Progresso — Pitch FIAP 2026-06-15 (NORA 100% Real + NORA Flows)

> Log vivo do run pareado (Anthony + Fable 5 Ultracode). Atualize a cada fatia.
> Goal canônico: ../../GOAL.md

## Como usar
- Marque cada item do DoD quando ficar **REAL** (não só codado: verificado de verdade).
- Registre HANDOFFs abertos/pendentes para não perder contexto entre sessões.
- Anote riscos para a revisão humana antes do palco.

## Cadeia de PRs (2026-06-11)
`#219` redesign v3 + sessões de chat (base de tudo) → `#220` Flows Fase 0 (event bus + engine +
e-mail real) → `#221` OAuth Google → `#227` Slack + gatilhos extras. Em paralelo sobre a `#220`:
`#223` canvas /fluxos → `#225` polish Fase 3 (PII badge, polling, sidebar viva, tags, export
MD/PDF) → `#226` hub de integrações + blocos Gmail/Calendar no canvas. Fora da cadeia (base
main): `#222` admin telemetria real, `#224` endpoints de settings.
**Ordem de merge sugerida: #219 → #220 → #221 → #227 → #223 → #225 → #226 → #222 → #224.**

## Status por fase
| Fase | Item | Estado | Verificado? | Notas |
|---|---|---|---|---|
| 0 | Event bus + MeetingAnalysisCompletedEvent | ✅ codado | IT verde | ADR 0030; porta DomainEventPublisher + adapter pós-commit; emissão em AnalysisService.run() fail-soft (PR #220) |
| 0 | Storage + engine Flows (V023+, RLS) | ✅ codado | IT verde | V023 (workflows + workflow_executions, RLS padrão V022); WorkflowEngine BFS + ConditionEvaluator + ActionRegistry; API /workflows CRUD + test + executions (PR #220) |
| 0 | Ação "Enviar e-mail" real (Resend) | ✅ codado | IT verde + envio real avulso aceito pelo Resend (id 24df5078) | EmailSender.sendWorkflowNotification PROPAGA falha; aguardando confirmação visual do Anthony na caixa |
| 0 | Spike OAuth Google | 🟨 backend pronto | 26 testes verdes (Google stubado) | ADR 0031: V024 + state HMAC + AES-GCM + refresh runtime + ações gmail_send_email/calendar_create_event (PR #221); FALTA handoff (projeto Google Cloud + client id/secret) |
| 1 | Canvas /fluxos (grid + nós + Testar) | ✅ codado | typecheck+build verdes; diff revisado | React Flow v12 (ADR 0032), lista + editor + execuções com log, nav/middleware/palette (PR #223); falta rodar ao vivo contra API |
| 1 | Cenário-âncora ao vivo | ⬜ pendente | — | depende de merge da cadeia + deploy (ou run local full-stack) |
| 2 | Gmail (OAuth real) | 🟨 backend pronto | IT verde (stub) | falta handoff Google Cloud + bloco no catálogo do canvas |
| 2 | Google Calendar (OAuth real) | 🟨 backend pronto | IT verde (stub) | idem |
| 2 | Slack (OAuth real) | 🟨 backend pronto | suíte 335 verde (Slack stubado) | PR #227: OAuth v2 + slack_post_message + hint de /invite; falta app Slack (handoff) |
| 3 | Settings (Conta/Segurança/Workspace) salvam | 🟨 backend pronto | 8 cenários IT verdes | PR #224: GET /auth/me, PATCH /users/me, password/change, logout-all, GET/PUT tenant, resend verificação; FALTA ligar o front (abas em /settings/context) |
| 3 | LGPD DELETE /users/me | 🟨 backend pronto | IT verde (exclusão + renascimento de e-mail) | senha obrigatória + guarda de tenant pessoal (409); falta front da zona de perigo |
| 3 | Chat sobrevive reload | ✅ já era real | conferido no mapeamento | sessões persistidas via ?s= (commit 7ecb528, PR #219); sidebar viva + rename/delete na branch de polish |
| 3 | Dashboard paginação + polling | ✅ já era real | conferido no mapeamento | PR #219; faltava só polling do DETALHE → branch de polish |
| 3 | Badge PII real | 🟨 na branch de polish | — | tipar metadata.piiRedactionsApplied (backend já retorna) |
| 3 | Export relatório (MD/PDF) | 🟨 na branch de polish | — | MD client-side + rota de impressão p/ PDF nativo |
| 3 | Admin saúde + métricas de negócio | ✅ codado | typecheck+build verdes | PR #222 — backend já existia, fatia foi só front |
| 4 | Mais gatilhos/ações/condições | 🟨 quase | IT TriggerEvents verde | PR #227: gatilhos action_item.created + meeting.risk_detected (só HIGH) emitidos pós-commit; 4 condições + 4 ações no engine; falta schedule.cron e ação criar tarefa |
| 4 | Templates + dry-run + polish | ⬜ pendente | — | |

## HANDOFFs humanos (abertos / resolvidos)
| Data | Pedido | Status | Resultado |
|---|---|---|---|
| 2026-06-11 | Confirmar e-mail de prova do Resend na caixa (axonogenesis@proton.me, assunto "NORA Flows - prova de envio real (Fase 0)") | 🟡 aberto | |
| 2026-06-11 | Criar projeto Google Cloud + OAuth Client ID/Secret + redirect URIs + habilitar Gmail/Calendar APIs (passos no .env.example da api) | 🟡 aberto | |
| 2026-06-11 | Autorizar merge da cadeia #219→#220→#221→#223 (CI verde) | 🟡 aberto | |

## Riscos para o palco
- OAuth Google em produção exige consent screen em modo Testing com o e-mail da demo como test user (verificação da tela leva dias — usar Testing).
- Admin em demo local mostra MOCK por default (`NORA_ADMIN_USE_MOCKS` só desliga com "false").
- Telemetria de custo usa janela de 24h — gerar tráfego antes da demo ou passar `from`.
- `CF_ACCESS_AUD` lido de vars (vazio) no deploy-infra — Tier 2 do admin degrada silencioso (bug pré-existente documentado).
- Evento do Flows é in-process sem retry: crash entre commit e dispatch perde o disparo (mitigação: botão Testar).

## Decisões registradas (ADR)
- ADR 0030 — event bus in-process pós-commit + workflow engine (PR #220)
- ADR 0031 — OAuth Google + armazenamento de tokens (AES-GCM, state HMAC) (PR #221)
- ADR 0032 — canvas com React Flow estilizado com tokens NORA (PR #223)
