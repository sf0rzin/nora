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
| 2026-06-11 | Confirmar e-mail de prova do Resend na caixa (axonogenesis@proton.me, assunto "NORA Flows - prova de envio real (Fase 0)") | ✅ resolvido | chegou na caixa de entrada |
| 2026-06-11 | Criar projeto Google Cloud + OAuth Client ID/Secret + redirect URIs + habilitar Gmail/Calendar APIs (passos no .env.example da api) | ✅ resolvido | consent em modo Testing; credenciais nas env vars User + GitHub Secrets |
| 2026-06-11 | Autorizar merge da cadeia #219→#220→#221→#223 (CI verde) | ✅ resolvido | TUDO mergeado (até #232) e DEPLOYADO; smoke verde em produção |
| 2026-06-11 | Criar app Slack (chat:write + channels:read) e setar SLACK_OAUTH_CLIENT_ID/SECRET | 🟡 aberto | wiring pronto no Bicep |
| 2026-06-11 | Validar ao vivo: conectar Google em /integracoes + cenário-âncora (fluxo → e-mail + Calendar) | 🟡 reteste | 1ª rodada (12/06) achou análise 422 + Calendar 400 — corrigidos e deployados; falta repetir o roteiro |

## Deploy (2026-06-11, fim do dia)
- Produção atualizada: API revision 34 Healthy com imagem `sha-80c9a06` (HEAD), web/admin/worker idem.
- Smoke em produção: healthz 200, resend 202, callback OAuth 302 → /integracoes, /workflows e /integrations 401 sem auth.
- 3 bugs de infra latentes corrigidos no caminho: corrida ServerIsBusy no Postgres (#229), secretRefs de embedding ausentes no apiApp (#230), e BOM UTF-8 nos GitHub Secrets gravados via pipe do PowerShell (regravados via --body; ver memória reference-gh-secret-bom-powershell).
- **Procedimento de deploy**: deploy-infra reseta as imagens para `:latest` (stale) — SEMPRE rodar `gh workflow run build-images.yml --ref main` depois, que re-pina `sha-<commit>` nas 4 apps.

## Bug-bash pós-teste do Grand Finale (2026-06-12)
Anthony rodou o roteiro ao vivo em produção e reportou problemas; todos diagnosticados e corrigidos no mesmo dia (PRs #235–#240, todos mergeados e deployados):
- **Análise falhando (422)** — `WorkerDtos` mandava `commercialPlaybook`/`keyFeatures`; o worker Pydantic (`extra="forbid"`) só aceita `objectionHandling`/`keyDifferentiators`. Mismatch antigo, exposto quando o deploy de 11/06 subiu a imagem nova do worker. Fix + `WorkerDtosContractTest` travando o contrato (#235). NÃO era a chave OpenAI (local = KV = válida, testadas contra a API).
- **calendar_create_event 400** — `OffsetDateTime.toString()` omite segundos zerados e o Google exige RFC3339 completo. Formatter explícito + corpo do erro do provedor no log da execução (#235). OAuth do Anthony estava perfeito (Gmail do mesmo fluxo saiu).
- **138× ClassCastException Instant→Timestamp em 12h** — cast cru nos adapters de chat sessions e tasks (sidebar polla sessões → travava a sidebar e "deixava o site lento"). Guarda `instanceof` (#235).
- **Site lento** — web rodava com 0.25 vCPU/0.5Gi e `minReplicas 0` (cold start a cada idle). Subido pra 1 vCPU/2Gi sempre quente + API 1 vCPU/2Gi, via az (efeito imediato) e persistido no Bicep (#236).
- **Logout espontâneo** — corrida benigna de refresh (multi-aba e timer+interceptor) caía na reuse detection e revogava a family inteira. Janela de tolerância de 60s no backend (ancorada no primeiro uso; logout não entra) + single-flight unificado no front (#238); IT realinhado ao contrato novo (#239 — a main ficou ~30min vermelha porque o agente não tinha Docker pro IT; lição: rodar ITs no CI antes de mergear).
- **Polish a pedido do PO** — admin 100% DM Sans sem mono (#237); scroll do Início no documento (causa raiz: scroller interno em `.app-main`), orbs removidos de Início/Projetos, switch Ativar/Pausado agrupado com Testar/Salvar no editor, avatares determinísticos estilo "macro desfocada" com 8 paletas (#240).
- **Pendente do feedback**: propriedades do Calendar dinâmicas a partir dos dados da reunião + confirmação quando a IA não souber o horário (feature — desenhar fatia mínima antes de codar; mexe no schema de análise a 3 dias do pitch).

## Mutirão de integrações + UX pós-reteste (noite de 2026-06-12, PRs #242–#250)
Reteste do Grand Finale pelo PO PASSOU (e-mail + Calendar reais). Na sequência, batch noturno:
- **Card pós-envio de transcrição** (#242): sem redirect automático — "Transcrição enviada" com status ao vivo (fila → analisando → pronta + CTA "Ver análise"), ações livres ("Enviar outra"/"Ir para o Início") e ponte com Flows (avisa que fluxos ativos vão executar; sem fluxo, sugere criar). Ideia do PO.
- **Catálogo de integrações** (#243): `docs/product/integracoes-possiveis.md` — 10 integrações grátis/multi-usuário com tutorial de credencial e env vars padronizadas.
- **Sidebar sem piscar + copy sóbria** (#244); **e-mail do Flows profissional** (#245): `MarkdownLite` (MD→HTML escape-first, sem lib) + template com moldura NORA — o resumo chegava com asteriscos literais.
- **Webhook genérico + Discord** (#246): `call_webhook` (POST JSON contrato estável, guarda SSRF resolvendo DNS e bloqueando ranges privados) e `discord_post_message` (embed via webhook de canal) + blocos no canvas. Zero credencial.
- **Framework OAuth + GitHub/Notion/Todoist/Linear** (#247): `OAuthProviderConfig`/`Directory` + `GenericOAuthHttpClient` (token exchange único; Google/Slack intactos), callback genérico `/{provider}/oauth/callback`, V025 expande CHECK de provider, 4 ações (issue/página/tarefa/issue por action item), hub + Bicep. ⚠️ no GitHub Actions o secret do GitHub chama `GH_OAUTH_CLIENT_ID/SECRET` (prefixo GITHUB_ é reservado).
- **Blocos no canvas dos 4 OAuth** (#248) — e descoberto gap antigo: slack_post_message não tinha bloco (coberto no #250).
- **Microsoft + Telegram + Trello** (#249): framework ganhou `supportsRefresh` (skew 60s, semântica do Google) pro Graph (`outlook_send_email`, `mscalendar_create_event`); Telegram SEM OAuth (pareamento por código TTL 10min + deep-link t.me + "Verificar conexão" via getUpdates; chat_id cifrado como conexão); Trello com token colado validado em /1/members/me. V026 expande providers. 355 testes.
- **Blocos finais no canvas** (#250): Slack, Outlook, Calendar MS, Telegram, Trello.
**Total: 13 ações de integração reais no Flows.** Lições operacionais: branches de agente precisam de merge da main + ajuste de aridade quando o contrato evolui em paralelo (ActionItemView ganhou dueDate no #246 e quebrou testes de #245/#247 — resolvido nas worktrees antes do CI); nunca editar arquivo acentuado com cmdlets do PowerShell (mojibake — usar Edit/perl).

**Aguardando credenciais do PO** (env vars locais → propagar): SLACK_OAUTH_*, GITHUB_OAUTH_* (repo secret: GH_OAUTH_*), NOTION_OAUTH_*, TODOIST_OAUTH_*, LINEAR_OAUTH_*, NORA_TELEGRAM_BOT_TOKEN, TRELLO_API_KEY, MS_OAUTH_*. Webhook/Discord funcionam sem credencial.

## Ingestão: Calendar pelo prazo, multi-upload e arquivo gigante (2026-06-12/13, PRs #252–#255)
- **Calendar agendado pelo prazo da reunião** (#252): `FollowUpSchedule` compartilhado Google+Outlook — sem `startInDays` explícito, o evento nasce no `dueDate` mais próximo (futuro estrito) dos action items extraídos; origem da data vai pro log da execução. Config manual do usuário sempre vence.
- **Multi-upload em lote** (#253): dropzone aceita vários arquivos; pool de concorrência 2 sobre `uploadMeeting`, progresso por arquivo + retry individual. Single-file inalterado.
- **Arquivo gigante com várias reuniões** (#254 worker+API, #255 front): worker `/split` segmenta transcrição mista (PII Shield **linha a linha** pra preservar mapeamento 1:1, LLM JSON estrito devolvendo fronteiras por linha, janelas de ~240k chars com merge de fronteiras; validação server-side anti-overlap/buraco); API `POST /meetings/split-preview` (.txt-only, sem persistir); front com toggle "separar automaticamente" → tela "Encontramos N reuniões" (título editável, incluir/excluir, preview redigido, badge âmbar < 0.7) → **fatiamento client-side por linha** → injeta no multi-upload do #253.
- **Revisão adversarial pré-merge do #255** (ultracode, 21 agentes, verificação por céticos): 8 achados confirmados, corrigidos no merge — (1) HIGH: worker numerava linha com `split('\n')` cru enquanto o front normaliza CRLF/CR→LF; CR-solto divergia a contagem → cortes deslocados (agora o worker normaliza igual, +2 testes de paridade); (2) colisão de slug de nome de arquivo desambiguada; (3) guard síncrono contra duplo-clique/race pós-cancelamento no `confirmSplit`; (4) erro >10MB em PT-BR (413 `FILE_TOO_LARGE`) no path do split. Falsos positivos descartados após verificação: badge de confiança herda `opacity` do pai (cascata correta); datas não propagadas ao split é correto (split = N reuniões distintas). SplitPreviewIntegrationTest 6/6.

## Riscos para o palco
- **Google em modo Testing: refresh token expira em 7 DIAS** — reconectar o Google em /integracoes na véspera (14/06). Verificação p/ público geral não dá até 15/06 (gmail.send é restricted scope: semanas + CASA).
- Admin em demo local mostra MOCK por default (`NORA_ADMIN_USE_MOCKS` só desliga com "false").
- Telemetria de custo usa janela de 24h — gerar tráfego antes da demo ou passar `from`.
- `CF_ACCESS_AUD` lido de vars (vazio) no deploy-infra — Tier 2 do admin degrada silencioso (bug pré-existente documentado).
- Evento do Flows é in-process sem retry: crash entre commit e dispatch perde o disparo (mitigação: botão Testar).
- Falta da Fase 4: gatilho schedule.cron, ação "criar tarefa", templates de fluxo e dry-run (não bloqueiam o roteiro da demo).

## Decisões registradas (ADR)
- ADR 0030 — event bus in-process pós-commit + workflow engine (PR #220)
- ADR 0031 — OAuth Google + armazenamento de tokens (AES-GCM, state HMAC) (PR #221)
- ADR 0032 — canvas com React Flow estilizado com tokens NORA (PR #223)
