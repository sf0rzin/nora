---
titulo: "Complete NORA Core front-end — handoff to the Design Architect"
status: active
autor: "NORA Architect (Tech Lead)"
data: 2026-06-09
verificado-em: 2026-06-09
---

# Complete NORA Core front-end (`apps/web`)

> **To: Claude Design (NORA's design architect).**
> Mission: deliver the **front-end for ALL NORA Core functions**, complete and
> with commercial-product UX, **by 15/06/2026**. This document is the full
> scope — the result of a multi-agent audit verified against the code on 2026-06-09.
> **The pages are in `apps/web/src/app`** — read the code before designing.
> Follow the current design system (§1). Do not invent a new one.

**Product decisions governing this handoff (Stratfy, 2026-06-09):**
1. **NORA Core is individual.** IAM, member invitations and corporate domain are
   **Enterprise** — they stay **out** of Core (§7). Do not design them, do not link them.
2. **Single font: DM Sans** (OpenAI feel). **Do not use mono** — neither JetBrains Mono
   nor any other — on any new screen; migrate the existing usages (§1).
3. **Scope = all Core functions** (§2–§5), not just spot fixes.

---

## 1. Design system — follow exactly this

⚠️ CLAUDE.md still cites "Inter + Instrument Serif" — **outdated** (v2).
The one in force is **v3** (June/2026 rebrand), with the font correction below.

| Aspect | Rule |
|---|---|
| **Font** | **DM Sans, a single family for EVERYTHING** (body, titles, labels, kbd). Declared in `apps/web/src/app/layout.tsx` via `next/font` (`--font-sans`). Weights: 400 body, **500 titles/buttons** (never 600/700 in a title), 600 only for strong/wordmark. **DO NOT use mono**: the section micro-labels currently in JetBrains Mono (`fontFamily: var(--mono)`, e.g. `meetings/[id]/page.tsx` and the dashboard footer) must **migrate to DM Sans** keeping the treatment (10.5–11px, uppercase, `letterSpacing 0.08em`, weight 500). |
| **Palette** | Tokens in `apps/web/src/styles/tokens.css` (source of truth). Cool-neutral light theme, "OpenAI feel": `--canvas #fdfdfc`, `--sidebar #f7f7f5`, `--chip #f0f0ee`, `--border #e7e7e3`, `--ink #15171a`, `--muted #6e7178`. **OKLCH blue hue 248** accent (`--accent`, `--accent-ink`, `--accent-soft`) used sparingly (active/links/focus). Signals `--success`/`--warn`/`--danger`. **Primary CTAs are BLACK** (`background var(--ink)`), not blue. |
| **Component language** | Inline styles consuming `var(--token)` (almost zero Tailwind classes). Page = container `maxWidth 760–920px`, padding `48–56px 40px 80px`. `Section` = small uppercase label + counter on the right + `borderBottom 1px var(--border)`. Chips = pill (`borderRadius 999`, `background var(--chip)`, 11–12px). Primary button = `var(--ink)` over `var(--canvas)`, radius 9, `padding 9px 15px`, 13px/500. Rows = `.nora-row` (hover `var(--sidebar)`, 120ms). Quotes = `var(--chip)` + `borderLeft 2px var(--accent)` + italic. Radii: 4 (kbd), 6–7 (nav), 9 (buttons), 10 (rows), 12–14 (cards), 999 (chips). |
| **Brand** | `NoraLogo` (5 soundwave bars) and `ShaderOrb` (blue WebGL orb) — empty states and the "Analisando…" status. |
| **Prohibitions (ADR 0013)** | Raw Tailwind, **no shadcn/Radix/CVA**. **No icon library** — hand-written inline SVG (13–16px, stroke 1.6–1.8). Motion 120–160ms, `--ease-out-expo`, respect `prefers-reduced-motion`. |
| **Language** | All UI text in **PT-BR**. No hardcoded TOTVS. No visible internal jargon (ADR, US##). |

**Read first, in this order:**
1. `apps/web/src/styles/tokens.css` — tokens
2. `apps/web/src/app/layout.tsx` — fonts
3. `apps/web/src/app/globals.css` — `.nora-prose`, `.nora-row`
4. `apps/web/src/components/core/app-shell.tsx` — shell/sidebar
5. `apps/web/src/app/(app)/dashboard/page.tsx` — **v3 model page**
6. `apps/web/src/app/(app)/meetings/[id]/page.tsx` — `Section`/chips/quotes pattern

---

## 2. The complete Core map (what exists × what is missing)

Core is complete with these surfaces. Everything that is "partial" or "does not exist" is
the scope of this handoff:

| Surface | State | What is missing (details in §3–§5) |
|---|---|---|
| Landing + Auth (login/signup/reset/verify) | ✅ v3 (login/signup) / ⚠️ `(card)/*` in the old style | Re-skin `(card)/*`; verification resend (§5.7) |
| Dashboard (meeting list) | ⚠️ Functional | Pagination; PROCESSING polling; complete tags; real shortcuts |
| Meeting upload | ⚠️ Functional, old style | v3 re-skin; participants + date/time fields |
| Meeting detail | ⚠️ Good, with loose ends | Slate cards; error≠404; interactive action items; real PII; polling |
| AI chat (the center of Core) | ⚠️ Functional | Stop/retry/persistence; suggestions without TOTVS/ADR |
| Action items (/tasks) | ⚠️ Functional | Consistent filtering; pt-BR dates; dueDate editor |
| Projects | ❌ Stub | **Build the tag-based MVP** (§4.6) |
| Integrations | ✅ Honest "Em breve" | Nothing |
| Settings → Contexto da empresa | ⚠️ Functional, old style | v3 re-skin; goes into the hub |
| Settings → **Conta / Segurança / Workspace** | ❌ **Do not exist** | **Build** (§4) — the core of the handoff |
| Mobile navigation | ❌ Does not exist | Build (§3.8) |

---

## 3. Complete what exists (front-end only; endpoint ready)

| # | Item | Where | Endpoint |
|---|---|---|---|
| 3.1 | **Unify the visual identity** — re-skin ALL the slate legacy to v3: `meetings/upload`, `settings/context`, `productivity-score-card`, `customer-confidence-card`, `meeting-productivity-section`, `meeting-goal-form`, `markdown-content`, `auth/(card)/*`, `app/error.tsx`, `not-found.tsx`, `loading.tsx`. Today the demo flow switches design system halfway through. | 20 files with `slate-*` | n/a |
| 3.2 | **Dashboard pagination** — stuck at 20; `totalPages/totalItems` arrive and are discarded | `dashboard/page.tsx:138-141` | `GET /meetings?page=&size=` |
| 3.3 | **PROCESSING polling** — detail/dashboard are static ("Volte em instantes" + F5); reuse the 2s polling from upload | `meetings/[id]/page.tsx:84-96` | `GET /meetings/{id}` |
| 3.4 | **Error ≠ 404** — any API failure in the detail turns into "page does not exist"; 500/timeout should fall into the error boundary | `meetings/[id]/page.tsx:36-42` | n/a |
| 3.5 | **Upload: participants + date/time** — the API already accepts `startedAt/endedAt/participants[]`; the form does not expose them | `meetings/upload/page.tsx:57-101` | `POST /meetings` |
| 3.6 | **Chat: stop + retry + persistence** — no AbortController, errors with no "try again", F5 loses everything; sessionStorage at minimum | `chat/page.tsx:30-95` | BFF ready |
| 3.7 | **Chat: suggestions** — remove the hardcoded "TOTVS" and "ADR 0012" from the empty state (1 line, violates a non-negotiable) | `chat/page.tsx:22-27` | n/a |
| 3.8 | **Mobile navigation** — the sidebar is `hidden md:flex` with no fallback; on a phone there is neither nav nor logout. Header + drawer | `app-shell.tsx:246` | n/a |
| 3.9 | **Real shortcuts** — the footer announces `N` / `/` / `⌘K` and there is no global listener at all. Implement: N → upload, / → search, ⌘K → palette with **semantic search** (`GET /meetings/search` — today only the chat BFF uses it; the dashboard search is substring-based) | `dashboard/page.tsx:223-234` | `GET /meetings/search?q=&k=` |
| 3.10 | **Remove goal** — `try/finally` with no catch (silent failure) + native `window.confirm`; use the typed-confirm pattern from `MeetingDangerZone` | `meeting-productivity-section.tsx:40-56` | `DELETE /meetings/{id}/goal` |
| 3.11 | **Tasks** — the item does not leave the list when the status changes with an active filter; raw dates with no pt-BR formatting (here and in the meeting detail) | `tasks/page.tsx:69-79, 218` | `PATCH /tasks/{id}` |
| 3.12 | **Tags** — the dashboard shows only `tags[0]`; show all of them (or +N) and in the detail too | `dashboard/page.tsx:102-104` | n/a |
| 3.13 | **Real PII Shield** — static "PII Shield aplicado" badge; the backend **already returns** `analysis.metadata.piiRedactionsApplied`: display "N dados sensíveis redigidos" (LGPD credibility before the examination board) | `meetings/[id]/page.tsx:201-206` | already comes in `GET /meetings/{id}` |
| 3.14 | **Cleanup** — `LogoutButton.tsx` is dead code (zero imports): remove it | `components/LogoutButton.tsx` | n/a |

---

## 4. Build what does not exist (new Core screens)

### 4.1 Settings hub
`app/(app)/settings/layout.tsx` with internal navigation. **Core** sections:
**Conta · Segurança · Workspace · Contexto da empresa**. (No IAM — see §7.)
The sidebar gear now opens the hub.

### 4.2 Conta (profile)
Edit `displayName`, view e-mail. *(Backend: `GET /auth/me` + `PATCH /users/me` — under construction by the Tech Lead, see §6.)*

### 4.3 Segurança
- **Change password while logged in** (current password + new one; today only logged-out reset exists). *(Backend §6.)*
- **Sign out of all devices**. *(Backend §6 — service already ready and tested.)*

### 4.4 Workspace
Workspace name + plan (the sidebar's "Core" badge is hardcoded today; signup
collects "Nome do workspace" and the backend **discards it**) + rename. *(Backend §6.)*

### 4.5 Danger zone — delete account/data (LGPD)
⚠️ The signup checkbox **promises** "posso solicitar exclusão dos meus dados".
Typed-confirm following the `MeetingDangerZone` pattern. *(Backend §6.)*

### 4.6 Projects (real MVP)
The vision promises "project tracking" in Core; the page is a permanent stub.
**Build the tag-based MVP**: group meetings/action items by tag (they already come in
`MeetingListItem.tags`), a card per project with counts and last activity,
drill-down to the filtered list. No new backend (client-side grouping).

### 4.7 Verification e-mail resend
Token expires → the user is stuck forever. A "Reenviar" button on the post-signup screen and
on the `EMAIL_NOT_VERIFIED` login error. *(Backend §6.)*

---

## 5. Polish (after §3 and §4)

1. Action items in the meeting detail: inline checkbox (client component) + "Ver em Action items" link.
2. Decorative Microsoft SSO: hide it behind a flag or an "Em breve" badge (not an error banner).
3. Consistent skeletons/empty/error states across all the new screens.
4. Original transcript (API+UI gap) — **do not do it** without a decision from Stratfy (ADR 0014).

---

## 6. Backend dependencies (the Tech Lead implements them — they do not block the design)

Design and build the complete screens; integrate with these contracts as soon as they are
published (the UI can degrade to an "unavailable" state until then):

| Endpoint | For | Backend status today |
|---|---|---|
| `GET /auth/me` | Conta | does not exist |
| `PATCH /users/me` (displayName) | Conta | does not exist (the `User` domain needs a mutation) |
| `POST /auth/password/change` | Segurança | does not exist (domain `changePasswordHash` + `PasswordPolicy` ready) |
| `POST /auth/logout-all` | Segurança | **`AuthService.logoutAllSessions()` ready and tested** — only the controller is missing |
| `GET /tenant` + `PUT /tenant/name` | Workspace | only `GET/PUT /tenant/domain` exists |
| `companyName` in signup | Workspace | the client already sends it; `SignupRequest` discards it |
| `DELETE /users/me` (LGPD) | Danger zone | does not exist (reuse `PrivacyService`) |
| `POST /auth/verify-email/resend` | Verification | does not exist |

---

## 7. Out of Core scope — DO NOT do

- **IAM, member invitations, corporate domain = Enterprise.** Core is
  individual (Stratfy decision 2026-06-09; the comment at `app-shell.tsx:9` already
  pointed this out). The `/settings/iam` page and the `invitation-card`/
  `corporate-domain-card`/`policy-editor` cards **stay as they are** (no link, no
  re-skin, not part of the hub) — they become an Enterprise surface post-v1.
- **ADR 0014 (v1 closed):** US05 corporate SSO · US08 audio/video · US21
  trends · US25 CSV/MD export · US27–US29 MCPs · US31 context history ·
  US33 usage metrics · US34 consolidated export · US41 policy templates ·
  US44 permission boundaries · US47 MCP project state · US50–US51 Account Health
  Score. The only candidate exception (Stratfy decision): US43 policy simulator.

---

## 8. Working rules

- **Branch + PR per slice** (`feat/`, `fix/`); never commit directly to main.
- After editing: `npm run typecheck` and `npm run build` in `apps/web` — report pass/fail.
- The app is **Next 15** (App Router): `params`/`searchParams`/`cookies()` are **Promises** — always `await`.
- Suggested order of attack: §3.1 (re-skin, unblocks everything visual) → §4.1 hub →
  §4.2–4.5 account screens → the rest of §3 → §4.6 Projects → §5.
- When in doubt about style, copy from `dashboard/page.tsx` and `meetings/[id]/page.tsx` —
  the v3 model pages (remembering: mono labels → DM Sans, §1).

---

*Multi-agent audit with adversarial verification (13 agents, 46 pending items
confirmed, 0 refuted) over `main` @ 2026-06-09, adjusted to Stratfy's product
decisions of 2026-06-09 (individual Core without IAM; DM Sans only; full scope
by 15/06).*
