# NORA Web

Front-end Next.js 14 (App Router) + TypeScript + Tailwind do projeto NORA.

## Pre-requisitos

- Node.js 20+
- pnpm ou npm

## Quickstart

```bash
cd apps/web
pnpm install            # ou: npm install
cp .env.example .env.local
pnpm dev                # http://localhost:3000
```

## Modo mock vs API real

- `NEXT_PUBLIC_USE_MOCKS=true` (padrao no skeleton): paginas renderizam a partir de
  `src/fixtures/*.json` (copias de `docs/api/examples/`). Permite trabalhar sem backend.
- `NEXT_PUBLIC_USE_MOCKS=false` + `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`:
  o cliente em `src/lib/api/client.ts` faz fetch real contra o NORA API.

## Estrutura

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

## Nota sobre shadcn/ui

A `tailwind.config.ts` ja inclui as variaveis de tema usadas pelo shadcn/ui, mas
nenhum componente foi pre-instalado (a CLI e interativa). Para adicionar componentes
depois: `npx shadcn@latest add button card ...`.
