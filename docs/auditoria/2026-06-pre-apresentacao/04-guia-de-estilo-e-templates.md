---
title: "Documentation style guide and templates"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
aplica_se_a: "All .md files in the repository (docs/, README, CLAUDE.md, SECURITY.md)"
---

# Documentation style guide and templates — NORA

> A single standard so that NORA's documentation looks like (and is) a real product. This
> document is the reference cited by diagnosis [03](03-diagnostico-documentacao.md).
> It was written following the very standard it describes.

## 1. Mandatory front-matter

Every document starts with YAML front-matter. Minimum:

```yaml
---
title: "Título legível do documento"
owner: <papel responsável>          # ex.: Arquiteto NORA (Tech Lead)
status: draft | review | approved | superseded
version: <semver simples, ex.: 1.0>
last_reviewed: AAAA-MM-DD
---
```

Optional fields where applicable: `supersedes` / `superseded_by` (ADRs and contracts),
`aplica_se_a`, `relacionado`.

## 2. Single source of truth (the golden rule)

Each fact lives in **one place only**; the other documents **link**, never recopy.

| Fact | Single source |
|---|---|
| ADR index and count | `docs/adr/README.md` |
| Migration range | `docs/engineering/data-model.md` |
| Status per user story | `docs/product/backlog.md` |
| Canonical terms | `docs/product/glossary.md` |

> Example of what **not** to do: writing "21 ADRs" in five documents. When the number
> changes, four are wrong. Write "see the [ADR index](docs/adr/README.md)".

## 3. Language policy

- **PT-BR** is the language of prose and of **all headings**.
- Technical terms in English are allowed *inline* when they are the industry term
  (e.g. *deploy*, *endpoint*, *commit*, *pull request*), in italics on first occurrence.
- Headings do not mix languages: use "Como trabalhamos", not "How To Work"; "Não
  negociáveis", not "Non-Negotiables".
- The glossary defines the canonical form of each term (e.g. "armadilhas" instead of "pegadinhas").

> **Future transition to English (bilingual front door):** if/when the repository is
> internationalized, the target standard is a README in English (front door) + deep
> docs in PT-BR, migrated progressively. Do not do this before the presentation.

## 4. Tone and register

**Allowed:** impersonal/third-person voice, direct sentences, concrete examples anchored to
`path:line`.

**Forbidden:**
- Slang: "shipou", "nukar", "gambiarra", "pegadinhas", "tô mexendo", "pra/pro" (use
  "para"/"pelo").
- **Emoji as semantic status** (✅ ⬜ ⚠️ 🚨). Use a textual status column
  (`Pendente` / `Em andamento` / `Concluído`) or an `Atenção:` label in text.
- Homoglyph characters (e.g. Cyrillic "а" in Latin text). A CI linter is recommended.

## 5. Recommended structure per document type

- **Product/engineering document**: front-matter → 1-paragraph summary (*blockquote*)
  → numbered sections → "Histórico do documento" at the end.
- **ADR**: see the template in §6.
- **Runbook**: see the template in §7.

Every document ends with a history table:

```markdown
## Histórico do documento

| Versão | Data | Autor | Mudança |
|---|---|---|---|
| 1.0 | AAAA-MM-DD | <papel> | Criação |
```

## 6. ADR template (MADR)

> Exemplary reference already in the repository: `docs/adr/0029-lgpd-operational.md`.

```markdown
# NNNN — Título da decisão

- Status: proposto | aceito | substituído por XXXX | obsoleto
- Data: AAAA-MM-DD
- Decisores: <papéis ou nomes>
- Relacionado: <ADRs, PRs, migrations relacionados> (opcional)

## Contexto
Qual problema/força motivou a decisão. Factual, sem solução ainda.

## Decisão
O que foi decidido, no presente do indicativo. Específico e verificável.

## Consequências
O que muda. **Incluir trade-offs negativos explícitos** (subseção "Negativas").

## Alternativas consideradas
Pelo menos uma alternativa real e por que foi rejeitada.
```

Rules: an accepted ADR is **immutable** (an obsolete decision → a successor ADR with `Status:
substituído por NNNN` in the original). Sequential 4-digit numbering, kebab-case.

## 7. Runbook template

```markdown
---
title: "Runbook — <operação>"
owner: <papel>
status: approved
version: 1.0
last_reviewed: AAAA-MM-DD
---

# Runbook — <operação>

> Objetivo em uma frase.

## Pré-requisitos
- Acessos, variáveis, ferramentas necessárias.

## Sequência (estado)

| Passo | Ação | Resultado esperado | Status |
|---|---|---|---|
| 1 | ... | ... | Pendente |

## Passos
1. Passo idempotente, com o comando exato.
   Atenção: avisos em texto, nunca em ALL-CAPS + emoji.

## Verificação (smoke)
Como confirmar que deu certo.

## Rollback
Como desfazer com segurança.
```

## 8. Cross-references

- **Relative** links with a section anchor: `[ADR 0029 — LGPD operacional](../adr/0029-lgpd-operational.md)`.
- When citing an ADR/migration, **verify that the number exists** (docs linter in CI).
- Avoid "see above/below"; link to the section.

## 9. Docs linter in CI (proposal)

A job that fails the PR when:
- Front-matter is missing or `last_reviewed` is older than N days.
- There is a broken relative link or a cited ADR/migration that does not exist.
- There is slang from the blacklist or emoji in a status position.
- There is a homoglyph character in Latin text.

## 10. Reconciliation checklist (for the PULL_REQUEST_TEMPLATE)

```markdown
- [ ] Docs vivos reconciliados (backlog/roadmap/vision/standards/data-model) se esta PR
      mudou status de US, migration ou ADR.
- [ ] Nenhum número de fato canônico (contagem de ADRs/migrations) foi recopiado —
      apenas linkado à fonte única.
- [ ] Front-matter atualizado (last_reviewed).
```

## Document history

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0 | 2026-06-06 | NORA Architect (Tech Lead) | Created as part of the pre-presentation audit |
