# Documentation diagnosis

> Assessment of the **professionalism and consistency** of 27 documents (`README`,
> `CLAUDE.md`, `SECURITY.md`, all of `docs/`). The standard proposed to fix what is
> described here is in document [04 — Style guide and templates](04-style-guide-and-templates.md).

## Conclusion in one sentence

NORA's documentation **has high-quality content** (architecture and ADRs rated
4–5), but suffers from **drift by freezing** (the docs stopped at ~2026-05-14/05-28
while engineering moved ahead ~58 PRs and 8 ADRs) and from **inconsistency of tone**
(slang, emoji-as-status, mixed PT/EN). The problem is not writing better — it is
**reconciling with the code** and **standardizing**.

## Score per document

> Scale 1–5 (5 = polished product/enterprise level).

| Document | Score | Main problem |
|---|---|---|
| `docs/adr/README.md` | 5 | — (it is the only correct source on the ADR count) |
| `docs/engineering/architecture.md` | 5 | Outdated migration anchors (V001–V012) |
| `docs/engineering/data-model.md` | 5 | Declares V001–V017; the real one is V021 |
| `docs/engineering/contracts/platform-control-plane.md` | 5 | "Frozen" while describing Easy Auth, superseded by ADR 0025 |
| `docs/product/vision.md` | 5 | "21 ADRs"; RAG/LGPD as future work (already delivered) |
| `docs/api/README.md` | 5 | The contract index does not list chat/RAG, privacy, control plane |
| `docs/challenge/README.md` | 5 | Frozen history (the pattern of the whole set) |
| Sampled ADRs (0001, 0014, 0029) | 5 | 0029 is the quality exemplar |
| `README.md` | 4 | "21 ADRs"; current state without RAG/control plane/LGPD |
| `CLAUDE.md` | 4 | Frozen ADR list; EN headings in a PT document |
| `SECURITY.md` | 4 | Wrong contact e-mail; public-repo tooling on a private repo |
| `docs/product/backlog.md` | 4 | US15 marked MISSING (RAG already delivered); frozen totals |
| `docs/product/roadmap.md` | 4 | "21 ADRs / V017 / #148"; the real figures are 29 / V021 / #206. Emoji + "gambiarra" (kludge) |
| `docs/engineering/standards.md` | 4 | Duplicated ADR table, stopped at 0021 |
| `docs/product/glossary.md` | 4 | Several obsolete entries (Customer Confidence, Conditions, RAG) |
| `docs/operations/production-readiness-gaps.md` | 4 | Gap 5 (LGPD) "pending" (already done); wrong cross-reference to ADR 0019 |
| `docs/api/llm-schemas/README.md` | 4 | "No markdown in the fields" contradicts `summary (markdown)` |
| `docs/challenge/fiap-challenge-2026.md` | 4 | "21 ADRs / 17 migrations"; scope 1.11 as future work (already delivered) |
| `docs/operations/azure-deploy.md` | 3 | "8 pegadinhas" (gotchas) + emoji as the canonical title; personal-notes tone |
| (remaining operations runbooks) | 4 | Emoji as status; Easy Auth × Cloudflare conflict between documents |

## The three structural problems

### A. Doc × code drift (the most serious one)

Items **confirmed against the code** that the docs still describe incorrectly:

| Claim in the docs | Reality in the code |
|---|---|
| "21 ADRs (0001–0021)" — in README, roadmap, vision, standards, fiap-challenge | **29 ADRs** (`docs/adr/0001..0029`). Only `adr/README.md` is correct. |
| "Migrations V001–V017" (or V012/V016) | **V021** (`V018` hash invitation token, `V019`/`V020` complete RLS/auth-aware scope, `V021` meeting_embeddings) |
| US15 "Semantic search — MISSING / post-MVP" | **Delivered**: `EmbeddingService.java`, `HttpEmbeddingClient.java`, `RagSearchIntegrationTest.java`, `V021`, PR #206 (Chat RAG) |
| Operational LGPD "pending / debt 1.12" | **Implemented**: ADR 0029, `DELETE /privacy/meetings/{id}`, `RetentionSweeper`, `PrivacyFlowIntegrationTest` |
| Customer Confidence "PARTIAL / persistence deferred" (glossary) | **DONE full-stack** (#148) |
| `PolicyEvaluator` "only StringEquals" (glossary) | Expanded: `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` |
| `packages/shared-contracts` "only `.gitkeep`" | 4 real files |
| Structure with `mcp/{calendar,tasks,crm}` | The `mcp/` folder **does not exist** |
| "Current state" dates: README 05-28, vision 05-21, backlog 05-14, roadmap 05-23 | The repo is already at **PR #206**; none of them reflects HEAD |

Missing from almost all product docs: **control plane / models console**
(ADR 0022–0025), **RLS cutover** (ADR 0026/0028) and **operational LGPD** (ADR 0029).

### B. Inconsistent tone and formatting

- **Slang** in stakeholder-facing docs: "pegadinhas" (gotchas — README, CLAUDE, standards, azure-deploy), "shipou" (shipped — backlog), "nukar" (nuke — production-readiness), "gambiarra" (kludge — roadmap), "tô mexendo aqui" (I'm messing with this — ADR 0014), the ubiquitous "pra/pro" (colloquial contractions of "para"/"para o").
- **Emoji as semantic status** (✅ ⬜ ⚠️ 🚨) in roadmap, azure-deploy, rls-cutover, cloudflare-access — inappropriate for due diligence/the examination board and fragile in a *diff*.
- **PT/EN mixing with no policy**: headings alternate language ("How To Work", "Non-Negotiables", "Responsible disclosure", "Definition of Done") within a PT-BR body.

### C. Multiple sources of truth that diverge

- The **ADR table** exists in `adr/README.md` (canonical, 0001–0029) **and** in `standards.md §12` (0001–0021, outdated).
- **Status per feature** appears in `backlog.md`, `vision.md §5` and `roadmap.md` at the same time — and they diverge.
- **Migration count** repeated in data-model, glossary, roadmap, fiap — all outdated.
- **Wrong cross-references**: `production-readiness-gaps` cites "ADR 0019" for LGPD (ADR 0019 is tenant isolation; LGPD is ADR 0029).
- **Conflict between operational docs**: `control-plane-runbook` and `cloudflare-access` adopt Cloudflare (ADR 0025), but the "frozen" contract `platform-control-plane.md` still describes Easy Auth — with no supersession note.

## Professionalization plan (phased)

So this does not turn into a huge PR on the eve of the presentation, phasing is recommended:

**Phase 1 — Credibility quick wins (before 15/06)** — high impact, low risk:
1. Fix the 9 *drift* claims from table A in the 4 most visible docs (README, vision, backlog, roadmap). Most of it is changing a number or moving a line from "future" to "delivered".
2. Fix the security e-mail in `SECURITY.md`.
3. Adopt a **single source of truth**: in the living docs, replace the ADR/migration count with a link to the canonical index, instead of recopying the number.

**Phase 2 — Standardization (after 15/06)** — apply the [style guide (04)](04-style-guide-and-templates.md):
1. YAML front-matter in all documents (owner, status, version, last_reviewed).
2. Language policy + removal of slang and emoji-as-status.
3. ADR and runbook templates applied retroactively.
4. Docs linter in CI (mandatory front-matter, valid links, homoglyphs).

**Phase 3 — Process (ongoing)**:
- Checklist in the `PULL_REQUEST_TEMPLATE`: *"living docs reconciled in this PR?"*.
- Doc × code reconciliation at every sub-phase that changes status, a migration or an ADR.

## Model document

As decided, **the `README.md` was rewritten on this branch** as a visual template for the
new standard: it applies front-matter, a professional tone, the language policy and — crucially —
the principle of a **single source of truth** (it links the ADR index instead of pinning down a
count that ages). Compare it via `git diff main -- README.md`.

The README was chosen as the model because it is the repository's front door (what
the evaluator/recruiter sees first). If an ADR is preferred as an alternative template, the
ADR 0029 already serves as an exemplar of the MADR standard.

## About language (PT-BR × English)

Recommendation for **now**: keep **PT-BR**, standardized. Professionalism comes from
consistency, not from language; the FIAP/TOTVS examination board is Brazilian; the product is LGPD/PT-BR
native. A possible "Americanization" of the GitHub repo (international portfolio) should be a
deliberate project **after the presentation**, ideally following the *bilingual front door* pattern
(README in English as the front door, deep docs in PT-BR). The style guide (04)
already includes a language policy prepared for that transition.
