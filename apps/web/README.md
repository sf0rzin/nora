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
`getMeeting`. The other 64 exported functions in that file always issue a real fetch. So on the
documented default, the meeting list and the meeting detail render from
`src/fixtures/*.json`, and `/tasks`, `/flows`, `/integrations`, `/projects`, `/settings/context`,
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
      flows/                    # workflow canvas: list, new, [id] (ADR 0030/0032)
      integrations/             # OAuth connector hub (ADR 0031)
      settings/context/         # tenant company/product context
      settings/iam/             # groups, policies, invitations, audit (no nav entry — see below)
    api/chat/route.ts           # BFF: the only server route, holds the provider key
  components/                   # flat, no feature folders
  lib/
    api/client.ts               # fetch wrapper; 66 exported functions
    api/types.ts                # types mirroring OpenAPI
  fixtures/                     # two files, see "Mock mode" above
  styles/                       # tokens.css + components.css
  middleware.ts                 # route protection
```

`/settings/iam` is fully wired to the API but nothing in the app links to it — the sidebar gear
and the command palette both point at `/settings/context`. It is reachable only by typing the URL.

## Scripts

```bash
npm run dev           # dev server
npm run build         # production build
npm run start         # serve build
npm run lint          # eslint (next/core-web-vitals)
npm run format        # prettier write
npm run format:check  # prettier check
npm run typecheck     # tsc --noEmit
npm run test:e2e      # playwright
npm run test:e2e:ui   # playwright, headed
```

There is no `test` script: this app has no unit-test runner. The Playwright suite under `e2e/`
covers routing, response headers and CSP violations only — no product behaviour.

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
