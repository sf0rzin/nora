---
titulo: "Front-end completo do NORA Core — handoff para o Arquiteto de Design"
status: ativo
autor: "Arquiteto NORA (Tech Lead)"
data: 2026-06-09
verificado-em: 2026-06-09
---

# Front-end completo do NORA Core (`apps/web`)

> **Para: Claude Design (arquiteto de design da NORA).**
> Missão: entregar o **front-end de TODAS as funções do NORA Core**, completo e
> com UX de produto comercial, **até 15/06/2026**. Este documento é o escopo
> integral — resultado de auditoria multi-agente verificada no código em 2026-06-09.
> **As páginas estão em `apps/web/src/app`** — leia o código antes de desenhar.
> Siga o design system atual (§1). Não invente um novo.

**Decisões de produto que regem este handoff (Stratfy, 2026-06-09):**
1. **NORA Core é individual.** IAM, convites de membros e domínio corporativo são
   **Enterprise** — ficam **fora** do Core (§7). Não desenhar, não linkar.
2. **Fonte única: DM Sans** (feel OpenAI). **Não usar mono** — nem JetBrains Mono
   nem nenhuma outra — em nenhuma tela nova; migrar os usos existentes (§1).
3. **Escopo = todas as funções do Core** (§2–§5), não só correções pontuais.

---

## 1. Design system — siga exatamente isto

⚠️ O CLAUDE.md ainda cita "Inter + Instrument Serif" — **desatualizado** (v2).
O vigente é o **v3** (rebrand jun/2026), com a correção de fonte abaixo.

| Aspecto | Regra |
|---|---|
| **Fonte** | **DM Sans, única família para TUDO** (corpo, títulos, labels, kbd). Declarada em `apps/web/src/app/layout.tsx` via `next/font` (`--font-sans`). Pesos: 400 corpo, **500 títulos/botões** (nunca 600/700 em título), 600 só strong/wordmark. **NÃO usar mono**: os micro-labels de seção hoje em JetBrains Mono (`fontFamily: var(--mono)`, ex.: `meetings/[id]/page.tsx` e rodapé do dashboard) devem **migrar para DM Sans** mantendo o tratamento (10.5–11px, uppercase, `letterSpacing 0.08em`, peso 500). |
| **Paleta** | Tokens em `apps/web/src/styles/tokens.css` (fonte da verdade). Tema claro cool-neutral, "OpenAI feel": `--canvas #fdfdfc`, `--sidebar #f7f7f5`, `--chip #f0f0ee`, `--border #e7e7e3`, `--ink #15171a`, `--muted #6e7178`. Accent **azul OKLCH hue 248** (`--accent`, `--accent-ink`, `--accent-soft`) com parcimônia (ativo/links/foco). Sinais `--success`/`--warn`/`--danger`. **CTAs primários são PRETOS** (`background var(--ink)`), não azuis. |
| **Idioma de componente** | Inline styles consumindo `var(--token)` (quase zero classes Tailwind). Página = container `maxWidth 760–920px`, padding `48–56px 40px 80px`. `Section` = label uppercase pequeno + contador à direita + `borderBottom 1px var(--border)`. Chips = pill (`borderRadius 999`, `background var(--chip)`, 11–12px). Botão primário = `var(--ink)` sobre `var(--canvas)`, radius 9, `padding 9px 15px`, 13px/500. Rows = `.nora-row` (hover `var(--sidebar)`, 120ms). Quotes = `var(--chip)` + `borderLeft 2px var(--accent)` + itálico. Raios: 4 (kbd), 6–7 (nav), 9 (botões), 10 (rows), 12–14 (cards), 999 (chips). |
| **Marca** | `NoraLogo` (5 barras soundwave) e `ShaderOrb` (orb WebGL azul) — empty states e status "Analisando…". |
| **Proibições (ADR 0013)** | Tailwind cru, **sem shadcn/Radix/CVA**. **Sem lib de ícones** — SVG inline à mão (13–16px, stroke 1.6–1.8). Movimento 120–160ms, `--ease-out-expo`, respeitar `prefers-reduced-motion`. |
| **Idioma** | Todo texto de UI em **PT-BR**. Sem TOTVS hardcoded. Sem jargão interno (ADR, US##) visível. |

**Leia primeiro, nesta ordem:**
1. `apps/web/src/styles/tokens.css` — tokens
2. `apps/web/src/app/layout.tsx` — fontes
3. `apps/web/src/app/globals.css` — `.nora-prose`, `.nora-row`
4. `apps/web/src/components/core/app-shell.tsx` — shell/sidebar
5. `apps/web/src/app/(app)/dashboard/page.tsx` — **página-modelo v3**
6. `apps/web/src/app/(app)/meetings/[id]/page.tsx` — padrão `Section`/chips/quotes

---

## 2. O mapa completo do Core (o que existe × o que falta)

O Core fecha com estas superfícies. Tudo que está "parcial" ou "não existe" é
escopo deste handoff:

| Superfície | Estado | O que falta (detalhe nos §3–§5) |
|---|---|---|
| Landing + Auth (login/signup/reset/verify) | ✅ v3 (login/signup) / ⚠️ `(card)/*` em estilo antigo | Re-skin `(card)/*`; reenvio de verificação (§5.7) |
| Dashboard (lista de reuniões) | ⚠️ Funcional | Paginação; polling de PROCESSING; tags completas; atalhos reais |
| Upload de reunião | ⚠️ Funcional, estilo antigo | Re-skin v3; campos participantes + data/hora |
| Detalhe da reunião | ⚠️ Bom, com pontas soltas | Cards slate; erro≠404; action items interativos; PII real; polling |
| Chat IA (centro do Core) | ⚠️ Funcional | Stop/retry/persistência; sugestões sem TOTVS/ADR |
| Action items (/tasks) | ⚠️ Funcional | Filtro consistente; datas pt-BR; editor de dueDate |
| Projetos | ❌ Stub | **Construir MVP por tags** (§4.6) |
| Integrações | ✅ "Em breve" honesto | Nada |
| Settings → Contexto da empresa | ⚠️ Funcional, estilo antigo | Re-skin v3; entra no hub |
| Settings → **Conta / Segurança / Workspace** | ❌ **Não existem** | **Construir** (§4) — núcleo do handoff |
| Navegação mobile | ❌ Não existe | Construir (§3.8) |

---

## 3. Completar o que existe (só frontend; endpoint pronto)

| # | Item | Onde | Endpoint |
|---|---|---|---|
| 3.1 | **Unificar identidade visual** — re-skin de TODO o legado slate p/ v3: `meetings/upload`, `settings/context`, `productivity-score-card`, `customer-confidence-card`, `meeting-productivity-section`, `meeting-goal-form`, `markdown-content`, `auth/(card)/*`, `app/error.tsx`, `not-found.tsx`, `loading.tsx`. O fluxo de demo troca de design system no meio hoje. | 20 arquivos c/ `slate-*` | n/a |
| 3.2 | **Paginação do dashboard** — trava em 20; `totalPages/totalItems` chegam e são descartados | `dashboard/page.tsx:138-141` | `GET /meetings?page=&size=` |
| 3.3 | **Polling de PROCESSING** — detalhe/dashboard estáticos ("Volte em instantes" + F5); reusar o polling de 2s do upload | `meetings/[id]/page.tsx:84-96` | `GET /meetings/{id}` |
| 3.4 | **Erro ≠ 404** — qualquer falha da API no detalhe vira "página não existe"; 500/timeout deve cair no error boundary | `meetings/[id]/page.tsx:36-42` | n/a |
| 3.5 | **Upload: participantes + data/hora** — API já aceita `startedAt/endedAt/participants[]`; form não expõe | `meetings/upload/page.tsx:57-101` | `POST /meetings` |
| 3.6 | **Chat: stop + retry + persistência** — sem AbortController, erro sem "tentar de novo", F5 perde tudo; mínimo sessionStorage | `chat/page.tsx:30-95` | BFF pronto |
| 3.7 | **Chat: sugestões** — remover "TOTVS" e "ADR 0012" hardcoded do empty state (1 linha, viola non-negotiable) | `chat/page.tsx:22-27` | n/a |
| 3.8 | **Navegação mobile** — sidebar é `hidden md:flex` sem fallback; em celular não há nav nem logout. Header + drawer | `app-shell.tsx:246` | n/a |
| 3.9 | **Atalhos reais** — rodapé anuncia `N` / `/` / `⌘K` e não existe nenhum listener global. Implementar: N → upload, / → busca, ⌘K → palette com **busca semântica** (`GET /meetings/search` — hoje só o BFF do chat usa; a busca do dashboard é substring) | `dashboard/page.tsx:223-234` | `GET /meetings/search?q=&k=` |
| 3.10 | **Remover objetivo** — `try/finally` sem catch (falha silenciosa) + `window.confirm` nativo; usar o padrão typed-confirm do `MeetingDangerZone` | `meeting-productivity-section.tsx:40-56` | `DELETE /meetings/{id}/goal` |
| 3.11 | **Tasks** — item não sai da lista ao mudar status com filtro ativo; datas cruas sem pt-BR (aqui e no detalhe da reunião) | `tasks/page.tsx:69-79, 218` | `PATCH /tasks/{id}` |
| 3.12 | **Tags** — dashboard mostra só `tags[0]`; exibir todas (ou +N) e no detalhe também | `dashboard/page.tsx:102-104` | n/a |
| 3.13 | **PII Shield real** — badge estática "PII Shield aplicado"; o backend **já retorna** `analysis.metadata.piiRedactionsApplied`: exibir "N dados sensíveis redigidos" (credibilidade LGPD na banca) | `meetings/[id]/page.tsx:201-206` | já vem no `GET /meetings/{id}` |
| 3.14 | **Limpeza** — `LogoutButton.tsx` é código morto (zero imports): remover | `components/LogoutButton.tsx` | n/a |

---

## 4. Construir o que não existe (telas novas do Core)

### 4.1 Hub de settings
`app/(app)/settings/layout.tsx` com navegação interna. Seções do **Core**:
**Conta · Segurança · Workspace · Contexto da empresa**. (Sem IAM — ver §7.)
A engrenagem do sidebar passa a abrir o hub.

### 4.2 Conta (perfil)
Editar `displayName`, ver e-mail. *(Backend: `GET /auth/me` + `PATCH /users/me` — em construção pelo Tech Lead, ver §6.)*

### 4.3 Segurança
- **Trocar senha logado** (senha atual + nova; hoje só existe reset deslogado). *(Backend §6.)*
- **Sair de todos os dispositivos**. *(Backend §6 — service já pronto e testado.)*

### 4.4 Workspace
Nome do workspace + plano (badge "Core" do sidebar hoje é hardcoded; o signup
coleta "Nome do workspace" e o backend **descarta**) + renomear. *(Backend §6.)*

### 4.5 Zona de perigo — excluir conta/dados (LGPD)
⚠️ O checkbox do signup **promete** "posso solicitar exclusão dos meus dados".
Typed-confirm no padrão `MeetingDangerZone`. *(Backend §6.)*

### 4.6 Projetos (MVP real)
A vision promete "rastreamento de projetos" no Core; a página é stub permanente.
**Construir o MVP por tags**: agrupar reuniões/action items por tag (já vêm em
`MeetingListItem.tags`), card por projeto com contagens e última atividade,
drill-down pra lista filtrada. Sem backend novo (agrupamento client-side).

### 4.7 Reenvio de e-mail de verificação
Token expira → usuário travado pra sempre. Botão "Reenviar" na tela pós-signup e
no erro de login `EMAIL_NOT_VERIFIED`. *(Backend §6.)*

---

## 5. Polish (depois do §3 e §4)

1. Action items no detalhe da reunião: checkbox inline (client component) + link "Ver em Action items".
2. SSO Microsoft decorativo: esconder atrás de flag ou badge "Em breve" (não banner de erro).
3. Skeletons/empty/error states consistentes em todas as telas novas.
4. Transcrição original (gap API+UI) — **não fazer** sem decisão da Stratfy (ADR 0014).

---

## 6. Dependências de backend (Tech Lead implementa — não bloqueiam o design)

Desenhe e construa as telas completas; integre com estes contratos assim que
publicados (a UI pode degradar com estado "indisponível" até lá):

| Endpoint | Para | Status no backend hoje |
|---|---|---|
| `GET /auth/me` | Conta | não existe |
| `PATCH /users/me` (displayName) | Conta | não existe (domínio `User` precisa de mutação) |
| `POST /auth/password/change` | Segurança | não existe (domínio `changePasswordHash` + `PasswordPolicy` prontos) |
| `POST /auth/logout-all` | Segurança | **`AuthService.logoutAllSessions()` pronto e testado** — falta só controller |
| `GET /tenant` + `PUT /tenant/name` | Workspace | só existe `GET/PUT /tenant/domain` |
| `companyName` no signup | Workspace | client já envia; `SignupRequest` descarta |
| `DELETE /users/me` (LGPD) | Zona de perigo | não existe (reusar `PrivacyService`) |
| `POST /auth/verify-email/resend` | Verificação | não existe |

---

## 7. Fora do escopo Core — NÃO fazer

- **IAM, convites de membros, domínio corporativo = Enterprise.** O Core é
  individual (decisão Stratfy 2026-06-09; o comentário do `app-shell.tsx:9` já
  apontava isso). A página `/settings/iam` e os cards `invitation-card`/
  `corporate-domain-card`/`policy-editor` **ficam como estão** (sem link, sem
  re-skin, sem entrar no hub) — viram superfície Enterprise pós-v1.
- **ADR 0014 (v1 fechada):** US05 SSO corporativo · US08 áudio/vídeo · US21
  tendências · US25 export CSV/MD · US27–US29 MCPs · US31 histórico de contexto ·
  US33 métricas de uso · US34 export consolidado · US41 templates de policy ·
  US44 permission boundaries · US47 MCP project state · US50–US51 Account Health
  Score. Única exceção candidata (decisão Stratfy): US43 simulador de policy.

---

## 8. Regras de trabalho

- **Branch + PR por fatia** (`feat/`, `fix/`); nunca commit direto na main.
- Depois de editar: `npm run typecheck` e `npm run build` em `apps/web` — reportar passou/falhou.
- O app é **Next 15** (App Router): `params`/`searchParams`/`cookies()` são **Promise** — sempre `await`.
- Ordem sugerida de ataque: §3.1 (re-skin, destrava tudo visual) → §4.1 hub →
  §4.2–4.5 telas de conta → §3 restante → §4.6 Projetos → §5.
- Em dúvida de estilo, copie de `dashboard/page.tsx` e `meetings/[id]/page.tsx` —
  páginas-modelo do v3 (lembrando: labels mono → DM Sans, §1).

---

*Auditoria multi-agente com verificação adversarial (13 agentes, 46 pendências
confirmadas, 0 refutadas) sobre `main` @ 2026-06-09, ajustada às decisões de
produto da Stratfy de 2026-06-09 (Core individual sem IAM; DM Sans única; escopo
completo até 15/06).*
