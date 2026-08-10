---
title: "Pre-presentation audit — Executive summary"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
contexto: "Preparation for NORA's 1st academic presentation (FIAP) on 2026-06-15"
---

# Pre-presentation audit — Executive summary

> Readiness audit of **NORA Core** for the first presentation (2026-06-15).
> It covers the three requested fronts: (1) gaps between back end and front end, (2) repository
> hygiene and (3) professionalization of the documentation. All findings were
> **verified against the code** (not merely inferred), with adversarial verification
> of the gaps to eliminate false positives.

## How this audit was produced

The analysis was conducted by a multi-agent orchestration (parallel inventory of the
back end, worker, web and admin → gap synthesis → adversarial verification of each
finding). Every candidate gap went through an independent agent whose task was
**to refute it** by looking for real consumption in the front end; only the confirmed ones survived.
Every suggested removal was verified by searching for references across the whole repository.

## Documents of this audit

| # | Document | Content |
|---|---|---|
| 01 | [Back end × front end gaps](01-gaps-backend-frontend.md) | Capabilities the back end exposes and the front end (web + admin) does not yet consume, prioritized for 15/06 |
| 02 | [Repository audit](02-repository-audit.md) | Items that can be safely removed, obsolete branches, configuration *smells* and what was verified and discarded |
| 03 | [Documentation diagnosis](03-documentation-diagnosis.md) | Doc × code *drift*, tone/consistency problems and a professionalization plan |
| 04 | [Style guide and templates](04-style-guide-and-templates.md) | Proposed standard for the documentation: front-matter, language policy, tone, ADR and runbook templates |

The **README** was rewritten on this same branch as the **model document** of the new standard
(see document 03, section "Documento-modelo").

## Executive conclusion

NORA Core is **solid and demonstrable end to end**. The central flow
(upload → analysis → summary/decisions/tasks → chat with RAG) is real and functional. The
findings below are about polish and readiness, not foundation.

### What needs attention before 15/06

| Priority | Item | Where | Effort |
|---|---|---|---|
| **Critical** | **"Reprocessar"** button on the web (recover an analysis that failed live) — the back end and the Desktop already have it; the web only shows the text "Tente reprocessar" with no action | [01 §1](01-gaps-backend-frontend.md) | Low |
| **Critical (config)** | Ensure `NEXT_PUBLIC_USE_MOCKS≠true` on the web **and** `NORA_ADMIN_USE_MOCKS=false` (+ tokens) on the admin in the demo environment — both display fictitious data by default | [01 §4](01-gaps-backend-frontend.md) | Configuration |
| High | Reconcile the *drift* of the product docs (ADR count, migrations, RAG/LGPD status) before any reading by the panel/evaluator | [03](03-documentation-diagnosis.md) | Medium |
| High (product) | The "right to be forgotten" (LGPD) is **announced on the landing page** but has no button that calls the already existing endpoint | [01 §1](01-gaps-backend-frontend.md) | Low |

> **Important:** none of these items blocks the demo's *happy path*. The only truly
> critical flow gap is the reprocess button; the rest is product differentiation,
> configuration readiness or documentation consistency.

### What is healthy (verified)

- Complete Core flow on the web: authentication, upload, status polling, summary in
  Markdown, decisions, action items, Productivity Score, Customer Confidence, chat with
  RAG by embeddings.
- AWS-style IAM operational (groups, versioned policies, audit log).
- Solid build hygiene: no build artifact committed; effective `.gitignore`.
- The technical quality of the ADRs and of the architecture is high — the documentation's weak point is
  consistency and tone, not content.

### Demo configuration risks (they are not code)

1. **Web in mock mode**: `NEXT_PUBLIC_USE_MOCKS=true` makes the dashboard/detail serve
   JSON *fixtures* instead of the API. Confirm it is off.
2. **Admin in mock mode by default**: `NORA_ADMIN_USE_MOCKS` is `true` unless
   explicitly `=false`. Without `PLATFORM_API_BASE_URL` + `PLATFORM_INTERNAL_TOKEN`, the
   console shows a fictitious catalog and any mutation disappears with no effect.

Details and the configuration checklist are in document [01, section 4](01-gaps-backend-frontend.md).
