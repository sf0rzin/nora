# NORA Web

The NORA project's Next.js 14 (App Router) + TypeScript + Tailwind front end.

## Prerequisites

- Node.js 20+
- pnpm or npm

## Quickstart

```bash
cd apps/web
pnpm install            # ou: npm install
cp .env.example .env.local
pnpm dev                # http://localhost:3000
```

## Mock mode vs real API

- `NEXT_PUBLIC_USE_MOCKS=true` (the skeleton default): pages render from
  `src/fixtures/*.json` (copies of `docs/api/examples/`). Allows working without a backend.
- `NEXT_PUBLIC_USE_MOCKS=false` + `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`:
  the client in `src/lib/api/client.ts` does a real fetch against the NORA API.

## Structure

```
src/
  app/
    layout.tsx              # raiz
    page.tsx                # / (login mock)
    (app)/
      layout.tsx            # shell autenticado (sidebar)
      dashboard/page.tsx    # lista de reunioes
      meetings/[id]/page.tsx
    globals.css
  lib/
    api/
      client.ts             # fetch wrapper, alterna mock/real
      types.ts              # tipos espelhando OpenAPI
    utils.ts                # cn(), formatDateTime
  fixtures/                 # copia de docs/api/examples/*.json
```

## Scripts

```bash
pnpm dev          # dev server
pnpm build        # build de producao
pnpm start        # serve build
pnpm lint         # eslint (next/core-web-vitals)
pnpm format       # prettier write
pnpm typecheck    # tsc --noEmit
```

## CSS Strategy (ADR 0013)

The project uses **raw Tailwind + CSS Modules** — `shadcn/ui` was **discarded** via ADR 0013.
Reasons: the OKLCH editorial palette, full control over tokens, and a monorepo policy that
forbids deps that trigger an interactive npx. Do not run `npx shadcn add`.

Legacy aliases (`background`, `foreground`, `primary`, `muted`, `border`) still
exist in `tailwind.config.ts` for compatibility with the meeting detail page,
which uses `text-muted-foreground` — being removed gradually.
