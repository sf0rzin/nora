# Architecture Decision Records — NORA

ADRs (Architecture Decision Records) record durable technical decisions with context and alternatives.

## Format

Use the lean MADR template:

```
# NNNN — Title

- Status: proposed | accepted | superseded by XXXX | obsolete
- Date: YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives Considered
```

There is no `Deciders` line. One person maintains this repository, so the field
only ever held an invented name.

## Numbering

Sequential, 4 digits, kebab-case: `0001-monorepo.md`, `0002-multi-tenancy.md`.

## When to Create an ADR

- A decision that is hard to reverse (database, framework, tenancy model, AI format).
- A decision that will surprise whoever arrives later.
- A decision made after discarding at least one real alternative.

## Immutability

**Accepted ADRs are immutable.** If a decision becomes obsolete:

1. Create a successor ADR (`NNNN-<slug>.md`) with `Status: supersedes XXXX`
2. Update the original ADR: `Status: superseded by NNNN`
3. Keep the original intact — it is the history of a decision that was made

Partially superseded decisions: a successor ADR may mark `Partially supersedes XXXX` (see ADR 0015 partially superseding ADR 0006).

## Index

| ID | Title | Status |
|---|---|---|
| 0001 | Monorepo with folders per application/service | accepted |
| 0002 | Multi-tenancy strategy: application filter in the MVP, RLS in production | accepted |
| 0003 | LLM output via mandatory JSON Schema | accepted |
| 0004 | LLM Provider strategy (agnostic, OpenAI as the default) | accepted |
| 0005 | Meeting Productivity Score (opt-in, based on a declared goal) | accepted |
| 0006 | Customer Confidence (per meeting) and Account Health (aggregate) | accepted (partially superseded by 0015) |
| 0007 | AWS-style IAM (Root + Users + Groups + Policies) | accepted |
| 0008 | Desktop App with Tauri 2 + Python Sidecar | accepted (Python sidecar superseded by 0035; Tauri 2 kept) |
| 0009 | Azure Speech credentials strategy | superseded by 0035 (the Azure Speech resource goes away via 0034) |
| 0010 | Shared `nlp-baseline` package for PT-BR TF-IDF | accepted |
| 0011 | Invite-based onboarding with optional corporate domain restriction | accepted |
| 0012 | PII PERSON_NAME: regional BR strategy in the MVP, NER upgrade when internationalizing | accepted |
| 0013 | Frontend CSS strategy (raw Tailwind, no shadcn, OKLCH tokens) | proposed (Design is refining) |
| 0014 | Defer post-MVP commercial gate (14 US deferred with a reactivation criterion) | superseded by 0038 (its gate was the FIAP pitch, held 2026-06-15) |
| 0015 | Customer Confidence — minimum viable persistence in Sub-phase 1.11 | accepted (partially supersedes 0006) |
| 0016 | Production-readiness checklist and `rg-nora-prod` separation | partially superseded by 0034 (the Azure premises of Gaps 1/3/4/7 fall; Gaps 2 and 6 hold on a different substrate; Gap 5 delivered by 0029) |
| 0017 | License: AGPL-3.0 | accepted |
| 0018 | Test coverage targets per critical area | accepted |
| 0019 | Tenant isolation in depth: Postgres RLS + composite FK | accepted |
| 0020 | Refresh token rotation + reuse detection (token families) | accepted |
| 0021 | Soft-delete strategy on tenant-owned entities | accepted |
| 0022 | Separate platform database + 2nd datasource (control plane) | accepted |
| 0023 | Operator identity (platform admin), separate from per-tenant IAM | accepted (Easy Auth superseded by 0025; edge changed by 0034) |
| 0024 | Dynamic model catalog + router by modality + runtime resolution | accepted (extends 0004) |
| 0025 | Operator identity v2: Cloudflare Tunnel + Access (supersedes Easy Auth from 0023) | accepted |
| 0026 | Complete RLS, versioned role provisioning and enforce cutover | partially superseded by 0028 (enforce/cutover design; V019+R001 kept) |
| 0027 | `main` branch protection + mandatory CI gate | accepted |
| 0028 | Auth-aware RLS enforcement: scope by data, Flyway-as-admin and cutover | accepted (fixes 0026) |
| 0029 | Operational LGPD: right to be forgotten + retention (hard-delete) | accepted |
| 0030 | NORA Flows: in-process post-commit event bus + workflow engine | accepted |
| 0031 | OAuth integrations (Google) and token storage | accepted |
| 0032 | NORA Flows canvas: React Flow styled with NORA tokens | accepted |
| 0033 | PII strategy on the chat path (structured in the BFF + PERSON_NAME via the worker) | accepted |
| 0034 | Migration from Azure Container Apps to self-hosted Proxmox (single VM + Docker Compose) | accepted (supersedes 0009; partially supersedes 0016; extends 0025; substrate §1 and backup §9 superseded by 0036) |
| 0035 | Local STT: Whisper embedded in Tauri (Rust), on the client machine | superseded by 0039 (it supersedes 0009 and partially supersedes 0008; those parts stand) |
| 0036 | The substrate is a single bare-metal Ubuntu host, not a Proxmox VM | accepted (supersedes 0034 §1 substrate and §9 backup) |
| 0037 | SSH reaches the host through the existing Cloudflare Tunnel, gated by Access | accepted (extends 0025 and 0034 §2) |
| 0038 | Post-pitch scope realignment | accepted (supersedes 0014) |
| 0039 | Cloud STT: OpenAI transcription reached with an ephemeral session token | accepted (supersedes 0035) |
| 0040 | The PII non-negotiable is scoped to analysis; transcription becomes a declared subprocessor | accepted (relates to 0012 and 0033; supersedes neither) |
| 0041 | NORA as an MCP server (the inbound path) | accepted (relates to 0031, the outbound path) |
| 0042 | Vitest in `apps/web`: which parts of the first unit suite are a gate | accepted (delivers the runner 0018 asked for; does not enforce 0018's web threshold table) |
| 0043 | ADDRESS becomes a redacted type, and the PII corpus gate becomes a decreasing target | accepted (extends 0012; pays the ADDRESS debt ADR 0040 recorded; supersedes neither) |
| 0044 | The RAG index gets a backfill path, triggered by an operator | accepted (extends 0024; relates to 0012 and 0028) |
| 0045 | The realtime STT session contract: one endpoint, one credential, no renewal loop | accepted (implements 0039; supersedes nothing) |
| 0046 | Finish the declared scope, and empty the limbo ADR 0038 left | accepted (extends 0038 §5; supersedes nothing) |
| 0047 | Scheduled Flows: a restricted vocabulary, a claimed run, and a window that outlives a crash | accepted (successor record to 0030 §5; supersedes nothing) |
| 0048 | Participant identity: deterministic matching over the declared roster | accepted (implements US13 of 0046 §1; relates to 0012 and 0029; supersedes nothing) |
| 0049 | Permission boundaries: a cap that never grants, and the four questions a cap raises | accepted (successor record to 0007; supersedes nothing) |
