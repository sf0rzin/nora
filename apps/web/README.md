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

`NEXT_PUBLIC_USE_MOCKS=true` is the default in `.env.example`, but it does **not** make the whole
app work without a backend, and the fixtures directory shows why: there are two of them.

`USE_MOCKS` is read in exactly two functions in `src/lib/api/client.ts` — `listMeetings` and
`getMeeting`. The other 67 exported functions in that file always issue a real fetch. So on the
documented default, the meeting list and the meeting detail render from
`src/fixtures/*.json`, and `/tasks`, `/trends`, `/flows`, `/integrations`, `/projects`, `/settings/context`,
`/settings/iam` and the chat sidebar all fail against a backend that is not there.

Set `NEXT_PUBLIC_USE_MOCKS=false` and `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080` to exercise
the real API — which is what every screen except two is doing regardless.

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
      flows/                    # workflow canvas: list, new, [id] (ADR 0030/0032)
      integrations/             # OAuth connector hub (ADR 0031)
      settings/                 # page.tsx redirects the bare prefix to context/
      settings/context/         # tenant company/product context
      settings/iam/             # groups, policies, invitations, audit
    api/chat/route.ts           # BFF: the only server route, holds the provider key
  components/                   # flat, no feature folders
  lib/
    api/client.ts               # fetch wrapper; 69 exported functions
    api/types.ts                # types mirroring OpenAPI
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
`src/**/*.test.ts`. Both packages export a global `test` and a global `expect`, so a glob that
crossed the line would have Vitest collect Playwright specs and fail confusingly; `vitest.config.mts`
keeps them apart.

**What each one covers, and what neither does.** The Playwright suite checks routing, response
headers and CSP violations against a real `next start` — no product behaviour, by design (see the
note at the top of `e2e/fixtures.ts`). The Vitest suite covers six `src/lib` modules: the
`request()` function that all 69 exported wrappers in `src/lib/api/client.ts` go through, the
Markdown report builder, the task-list CSV/Markdown exporter, the BFF's PII redaction, the password
policy and the trends panel's date/axis helpers. **No page and no component has a unit test**, which
is why whole-app coverage is around 6%.

Two of those tests are mirrors and read files from other services: `src/lib/pii/redact.test.ts`
compares its pattern literals with the worker's PII Shield, and `src/lib/password-policy.test.ts`
compares its constants with the backend's `PasswordPolicy` and DTO bounds. They fail loudly if
those files move — do not turn that into a skip.

`npm run test:coverage` is also the gate: `vitest.config.mts` declares per-module coverage floors
on `redact.ts`, `markdown.ts`, `tasks-export.ts` and `password-policy.ts`, each set below the
measured rate so it fires on a regression. There is no whole-app threshold, and none on
`client.ts`. ADR 0042 has the reasoning; `scripts/report-coverage.sh web` prints both scopes.

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
