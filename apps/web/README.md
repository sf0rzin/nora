# NORA Web

The NORA project's Next.js 16 (App Router) + TypeScript + Tailwind front end. It is also the
BFF: provider keys stay server-side and the session is an httpOnly cookie.

## Prerequisites

- Node.js 22 (what CI uses)
- npm — this app has a `package-lock.json` and no other lockfile, and CI runs `npm ci`

## Quickstart

```bash
cd apps/web
npm install
cp .env.example .env.local
npm run dev             # http://localhost:3000
```

## Mock mode vs real API

`NEXT_PUBLIC_USE_MOCKS=false` is the default in `.env.example`, and the quickstart above therefore
needs the backend running. That default was `true` until 2026-08-23, which made the documented
first run produce an application where most screens failed: `USE_MOCKS` is read in exactly two
functions in `src/lib/api/client.ts` — `listMeetings` and `getMeeting` — and the other 77 exported
functions always issue a real fetch. Eight broken screens is a worse first impression than one
clear failure at the first request, and the client itself had already been flipped to default off
for the same reason (a build that forgot the variable used to serve fixtures in production).

Set `NEXT_PUBLIC_USE_MOCKS=true` deliberately when you want to look at the dashboard and the meeting
detail with no backend at all. Everything else — `/tasks`, `/trends`, `/flows`, `/integrations`,
`/projects`, `/settings/context`, `/settings/iam` and the chat sidebar — needs the API either way.
The two fixtures in `src/fixtures/` are kept in step with what the API actually returns, including
the fields that are usually absent (`productivityBand`, `participants`), so mock mode does not hide
features that exist.

## Structure

```
src/
  app/
    page.tsx                    # / — public landing
    layout.tsx                  # root
    auth/                       # login, signup, verify e-mail, password reset, invite accept
    (app)/                      # authenticated shell
      chat/                     # the Core surface: chat over meetings, with RAG
      dashboard/                # chronological meeting inbox
      meetings/upload/
      meetings/[id]/            # detail, plus report/ for the printable view
      tasks/  projects/
      trends/                   # US21 panel: task load over time + recurring themes
      usage/                    # US33 panel + US34 period report: meetings, AI calls, cost
      flows/                    # workflow canvas: list, new, [id] (ADR 0030/0032)
      integrations/             # OAuth connector hub (ADR 0031)
      settings/                 # page.tsx redirects the bare prefix to context/
      settings/context/         # tenant company/product context
      settings/iam/             # groups, policies, invitations, audit
    api/chat/route.ts           # BFF: the streaming chat route, holds the provider key
    api/csp-report/route.ts     # collector the Report-Only CSP points at
  components/                   # flat, no feature folders
  lib/
    api/client.ts               # fetch wrapper; 79 exported functions
    api/types.ts                # types mirroring OpenAPI
    chat/ projects/ iam/        # logic lifted out of the screens so it can be tested
  fixtures/                     # two files, see "Mock mode" above
  styles/                       # tokens.css + components.css
  middleware.ts                 # route protection
```

`/settings/iam` is fully wired to the API and is reached from the administration section of the
sidebar and from the command palette. The entry is shown to every signed-in user: whether the
caller may actually read groups, policies or the audit log is decided by the backend's `iam:*`
permissions, not by hiding the link.

## Scripts

```bash
npm run dev           # dev server
npm run build         # production build
npm run start         # serve build
npm run lint          # eslint (next/core-web-vitals)
npm run format        # prettier write
npm run format:check  # prettier check
npm run typecheck     # tsc --noEmit
npm run test          # vitest, unit suite under src/
npm run test:watch    # vitest, watch mode
npm run test:coverage # vitest + v8 coverage, applies the per-module thresholds
npm run test:e2e      # playwright
npm run test:e2e:ui   # playwright, headed
```

Two suites, and they never see each other's files: Playwright owns `e2e/`, Vitest owns
`src/**/*.test.{ts,tsx}`. Both packages export a global `test` and a global `expect`, so a glob that
crossed the line would have Vitest collect Playwright specs and fail confusingly; `vitest.config.mts`
keeps them apart.

**What each one covers.** The Playwright suite checks routing, response headers and CSP violations
against a real `next start` — no product behaviour, by design (see the note at the top of
`e2e/fixtures.ts`). The Vitest suite lives in `src/**/*.test.{ts,tsx}`, one file next to the module
or screen it covers; `find src -name "*.test.ts*"` is the list, and this document deliberately does
not repeat it.

Until 2026-08-23 it covered pure `src/lib` modules only and no page or component had a test at all.
It now also covers behaviour that had none and had already broken in production: the NDJSON framing
between `POST /api/chat` and the chat screen (`src/lib/chat/`), the 401 refresh on the chat's own
call, the flow editor's unsaved-work guard, the projects screen's arithmetic and paging, and the IAM
screen's field labelling, group membership and two-step deletes.

Two tests are mirrors and read files from other services: `src/lib/pii/redact.test.ts` compares its
pattern literals with the worker's PII Shield, and `src/lib/password-policy.test.ts` compares its
constants with the backend's `PasswordPolicy` and DTO bounds. They fail loudly if those files move —
do not turn that into a skip.

`npm run test:coverage` is also the gate: `vitest.config.mts` declares per-module coverage floors,
each set below the measured rate so it fires on a regression. **The list of gated modules lives in
that file and nowhere else** — every copy of it in a document has been wrong at some point, in a
different way. There is no whole-app threshold, and none on `client.ts` (the reasoning is in the
config, next to the omission). ADR 0042 has the rest; `scripts/report-coverage.sh web` prints both
scopes.

**No coverage percentage is quoted here, on purpose.** Four documents in this repository published
four different web coverage numbers and three different counts of gated modules, none of them
matching `vitest.config.mts`; every one had been correct when written. Read the last CI run.

## CSS strategy (ADR 0013)

Raw Tailwind. `shadcn/ui` was discarded via ADR 0013 — reasons: the OKLCH editorial palette, full
control over tokens, and a monorepo policy against dependencies that trigger an interactive npx.
Do not run `npx shadcn add`.

There are **no CSS Modules** in this app: zero `.module.css` files exist. What the codebase
actually uses alongside Tailwind is `src/styles/` for shared classes and roughly 684 inline
`style={{}}` objects. That is a real trade-off ADR 0013 did not anticipate, not a convention to
copy deliberately.

Legacy aliases (`background`, `foreground`, `primary`, `muted`, `border`) still exist in
`tailwind.config.ts` for the meeting detail page, which uses `text-muted-foreground` — being
removed gradually.
