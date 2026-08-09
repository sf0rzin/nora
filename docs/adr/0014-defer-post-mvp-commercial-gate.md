# 0014 — Defer post-MVP commercial gate

- Status: accepted
- Date: 2026-05-14
- Deciders: Stratfy (PO), Tech Lead, Design Architect

## Context

The backlog declares 57 stories (US01-US51 numbered + 6 conditional). Actual status after Sub-phase 1.9 (real Azure deploy, 2026-05-13):

- **28 DONE**
- **5 PARTIAL**
- **10 MISSING** (5 of which are marked W = Won't Have v1 in the original backlog)
- 9 categorized as Should/Could/Won't

A pace of 10 sub-phases in ~10 agentic days is unsustainable for another 10 sub-phases without burnout or loss of focus. The Design Architect's review in the pre-Sub-phase 1.10 audit made the risk explicit:

> "Stop adding USs. The backlog declares v1 closed. The next sub-phases deliver only within the declared v1."

The PO's 3 plans (memory `user_career.md`):
- **Plan A** (TOTVS hires): ~70% mature, ~2 weeks to demo-ready
- **Plan B** (commercial SaaS): ~25%, requires a business co-founder
- **Plan C** (LinkedIn/portfolio): already mature

The `mcp/{calendar,tasks,crm}/` folder committed empty projects "incomplete" to any technical reviewer who clones the repo.

## Decision

Declare the backlog's **v1 closed after Sub-phase 1.12 (Production Hardening)**. List of USs explicitly **deferred** (not permanently MISSING, reactivatable under a criterion):

| US | Title | Defer until | Who decides to reactivate |
|---|---|---|---|
| US05 | SSO Entra ID / SAML | Plan A demo closed OR 100 Plan B tenants | Stratfy (commercial decision) |
| US08 | Audio/video upload (Azure Speech upload) | Ditto | Ditto |
| US15 | Semantic search with embeddings (AI Search) | Cost model adds up OR the FIAP pitch justifies it | Tech Lead (cost: AI Search Basic ~R$400/month) |
| US21 | Trends panel (Could) | Post Plan A demo | Design Architect (UX value) |
| US25 | Export tasks CSV/MD (Should) | Sub-phase 1.13+ | Tech Lead |
| US33 | Tenant usage metrics (Should) | Plan B onboarding | Tech Lead |
| US34 | Export consolidated report (Should) | Sub-phase 1.13+ | Tech Lead |
| US41 | Policy templates (Should) | Sub-phase 1.13+ | Tech Lead |
| US43 | Policy simulator (Should) | Sub-phase 1.13+ | Tech Lead |
| US44 | Permission boundaries (Could) | Post Plan A demo | Tech Lead |
| US47 | MCP project state (Won't) | Plan B integration | Stratfy |
| US50 | Aggregated Account Health Score | Sub-phase 1.13+ (after the US48-49 base is stable via ADR 0015) | Tech Lead |
| US51 | Band-change alert | Sub-phase 1.13+ | Tech Lead |

**US48-49 (Customer Confidence base) are handled separately in ADR 0015** — minimal implementation in 1.11.

The `mcp/{calendar,tasks,crm}/` folder is removed from the monorepo root (or moved to `archive/mcp-future/` in `.gitignore`) — the visual "incomplete" signal is eliminated.

## Consequences

**Positive:**
- The backlog stops growing during the critical pre-pitch window (until 12/06)
- Roadmap.md becomes realistic and prioritizable
- Stratfy and the architects focus on **polishing what exists** vs adding features nobody sees in the MVP
- Technical reviewers see a coherent product, not "100 unfinished promises"
- Plan C content has focus (8 Azure gotchas, AWS-style IAM, BR PII, etc.) without being diluted into "I also have Y and Z"

**Negative:**
- Stratfy may feel the temptation of "just this one I'll add" — discipline needs to be active
- If the Plan A demo does not close, the list of deferred USs needs to be revisited with clarity about which ones to reactivate
- Risk of "silent scope creep" — fixes that turn into features. Mitigation: PR review asks "is this within the scope of the declared sub-phase?"

## Alternatives Considered

1. **Keep expanding the backlog** — rejected for the reason in the context (unsustainable pace + team fatigue + visual "incomplete" signal)
2. **Delete the deferred USs from the backlog** — rejected because it loses traceability. History is worth keeping.
3. **Mark W (Won't Have v1) on all deferred ones** — halfway there. We chose an explicit **"formal Defer"** because it is **reactivatable** under a criterion; W suggests "forever".

## Application Plan

1. **`docs/product/backlog.md`** updated: real status + an explicit "DEFERRED — reactivate when X" mark on the 13 listed USs
2. **`docs/product/roadmap.md`** describes Sub-phase 1.11 and 1.12 with declared scope; sub-phases 1.13+ remain open pending traction
3. The **`arquiteto-nora` skill** already has the anti-pattern "Accepting scope creep ('since I'm in here anyway, I'll add Y') — declared scope is executed scope" — an explicit reference to this ADR

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Stratfy (PO) | Approved as a block after the Design Architect's review + Tech Lead recommendation. Reactivation criterion documented per US |
