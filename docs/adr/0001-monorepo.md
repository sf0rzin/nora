# 0001 — Monorepo com pastas por aplicação/serviço

- Status: aceito
- Data: 2026-05-02
- Decisores: Time NORA

## Contexto

NORA tem múltiplos artefatos: web (Next.js), backend (Spring Boot), worker (FastAPI), futuro desktop (Tauri), MCPs e infra. O time é pequeno (2 pessoas + IA), o ciclo de release é curto e os contratos entre serviços precisam evoluir juntos.

## Decisão

Usar **monorepo único** no GitHub com layout:

```
apps/{web,desktop}
services/{api,nlp-worker}
packages/shared-contracts
mcp/{calendar,tasks,crm}
infra/{bicep,docker}
docs/
```

CI separa pipelines por path filter. Cada serviço tem seu próprio `Dockerfile` e ferramental nativo (Maven, pnpm, uv/poetry).

## Consequências

- Mudanças de contrato (OpenAPI / JSON schema) entram em um único PR cobrindo todas as pontas.
- Onboarding e revisão simplificados: um clone, um histórico.
- CI precisa de filtros por path para evitar builds lentos.
- Releases são por tag de serviço (`api-v0.1.0`, `web-v0.1.0`) e não por tag única do repo.

## Alternativas Consideradas

- **Polyrepo (1 repo por serviço).** Rejeitado: alto custo de coordenação para 2 pessoas e contratos compartilhados.
- **Monorepo com Nx/Turborepo.** Adiado: ainda não compensa a complexidade extra para o tamanho atual; pode ser introduzido se o web precisar compartilhar libs internas.
