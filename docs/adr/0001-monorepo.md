# 0001 — Monorepo with folders per application/service

- Status: accepted
- Date: 2026-05-02

## Context

NORA has multiple artifacts: web (Next.js), backend (Spring Boot), worker (FastAPI), future desktop (Tauri), MCPs and infra. The team is small (2 people + AI), the release cycle is short, and the contracts between services need to evolve together.

## Decision

Use a **single monorepo** on GitHub with the layout:

```
apps/{web,desktop}
services/{api,nlp-worker}
packages/shared-contracts
mcp/{calendar,tasks,crm}
infra/{bicep,docker}
docs/
```

CI separates pipelines by path filter. Each service has its own `Dockerfile` and native tooling (Maven, pnpm, uv/poetry).

## Consequences

- Contract changes (OpenAPI / JSON schema) land in a single PR covering all ends.
- Onboarding and review are simplified: one clone, one history.
- CI needs path filters to avoid slow builds.
- Releases are per service tag (`api-v0.1.0`, `web-v0.1.0`) and not a single repo tag.

## Alternatives Considered

- **Polyrepo (1 repo per service).** Rejected: high coordination cost for 2 people and shared contracts.
- **Monorepo with Nx/Turborepo.** Deferred: the extra complexity is not yet worth it at the current size; it can be introduced if web needs to share internal libs.
