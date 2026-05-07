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

## Índice

| ID | Título | Status |
|---|---|---|
| 0001 | Monorepo com pastas por aplicação/serviço | aceito |
| 0002 | Estratégia de multi-tenancy: filtro de aplicação no MVP, RLS em produção | aceito |
| 0003 | Saída do LLM via JSON Schema obrigatório | aceito |
| 0004 | Estratégia de Provider de LLM (agnóstica, OpenAI como default) | aceito |
| 0005 | Desktop App com Tauri 2 + Sidecar Python | aceito |
