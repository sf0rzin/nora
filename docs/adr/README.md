# Architecture Decision Records — NORA

ADRs (Architecture Decision Records) registram decisões técnicas duráveis com contexto e alternativas.

## Formato

Usar o template MADR enxuto:

```
# NNNN — Título

- Status: proposto | aceito | substituído por XXXX | obsoleto
- Data: AAAA-MM-DD
- Decisores: nomes ou times

## Contexto
## Decisão
## Consequências
## Alternativas Consideradas
```

## Numeração

Sequencial, 4 dígitos, kebab-case: `0001-monorepo.md`, `0002-multi-tenancy.md`.

## Quando Criar um ADR

- Decisão difícil de reverter (banco, framework, modelo de tenancy, formato de IA).
- Decisão que vai surpreender quem chegar depois.
- Decisão tomada após descartar pelo menos uma alternativa real.

## Imutabilidade

**ADRs aceitos são imutáveis.** Se uma decisão fica obsoleta:

1. Crie um ADR sucessor (`NNNN-<slug>.md`) com `Status: substitui XXXX`
2. Atualize o ADR original: `Status: substituído por NNNN`
3. Mantenha o original intacto — ele é histórico de uma decisão tomada

Decisões parcialmente substituídas: ADR sucessor pode marcar `Substitui parcialmente XXXX` (ver ADR 0015 substituindo parcialmente ADR 0006).

## Índice

| ID | Título | Status |
|---|---|---|
| 0001 | Monorepo com pastas por aplicação/serviço | aceito |
| 0002 | Estratégia de multi-tenancy: filtro de aplicação no MVP, RLS em produção | aceito |
| 0003 | Saída do LLM via JSON Schema obrigatório | aceito |
| 0004 | Estratégia de Provider de LLM (agnóstica, OpenAI como default) | aceito |
| 0005 | Productivity Score da reunião (opt-in, baseado em objetivo declarado) | aceito |
| 0006 | Customer Confidence (por reunião) e Account Health (agregado) | aceito (parcialmente substituído por 0015) |
| 0007 | IAM estilo AWS (Root + Users + Groups + Policies) | aceito |
| 0008 | Desktop App com Tauri 2 + Sidecar Python | aceito |
| 0009 | Estratégia de credenciais Azure Speech | aceito |
| 0010 | Package compartilhado `nlp-baseline` para TF-IDF PT-BR | aceito |
| 0011 | Invite-based onboarding com restrição opcional de corporate domain | aceito |
| 0012 | PII PERSON_NAME: estratégia regional BR no MVP, upgrade NER quando internacionalizar | aceito |
| 0013 | Estratégia de CSS frontend (Tailwind cru, sem shadcn, tokens OKLCH) | proposto (Design refina) |
| 0014 | Defer post-MVP commercial gate (14 US adiadas com critério de reativação) | aceito |
| 0015 | Customer Confidence — persistência mínima viável na Sub-fase 1.11 | aceito (substitui parcialmente 0006) |
| 0016 | Production-readiness checklist e separação `rg-nora-prod` | proposto (aceita na 1.12) |
| 0017 | License: AGPL-3.0 | aceito |
| 0018 | Test coverage targets por área crítica | aceito |
| 0019 | Tenant isolation em profundidade: RLS Postgres + FK composta | aceito |
| 0020 | Rotação de refresh token + detecção de reuso (token families) | aceito |
| 0021 | Estratégia de soft-delete em entidades tenant-owned | aceito |
| 0022 | Banco de plataforma separado + 2º datasource (control plane) | aceito |
| 0023 | Identidade de operador (platform admin), separada do IAM por-tenant | aceito (Easy Auth substituído por 0025) |
| 0024 | Catálogo de modelos dinâmico + router por modalidade + resolução runtime | aceito (estende 0004) |
| 0025 | Identidade de operador v2: Cloudflare Tunnel + Access (substitui Easy Auth do 0023) | aceito |
| 0026 | RLS completa, provisionamento de role versionado e cutover do enforce | parcialmente substituído por 0028 (design de enforce/cutover; V019+R001 mantidos) |
| 0027 | Branch protection da `main` + CI gate obrigatório | aceito |
| 0028 | RLS enforcement auth-aware: escopo por dado, Flyway-as-admin e cutover | aceito (corrige 0026) |
| 0029 | LGPD operacional: direito ao esquecimento + retenção (hard-delete) | aceito |
| 0030 | NORA Flows: event bus in-process pós-commit + workflow engine | aceito |
| 0032 | Canvas do NORA Flows: React Flow estilizado com tokens NORA | aceito |
