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

**A proposed ADR that the code has already implemented.** Immutability starts at acceptance, so this
one has a path of its own and it is not "leave it Proposed". Accept it in place, with the date of
acceptance beside the date of drafting, and add a History row saying **what acceptance covers and
what it does not** — normally the Decision section only, because the Context was written earlier and
describes a tree that has moved. Do not edit the Context to agree with today: that is the rewrite
the rule above forbids, one step early. If the drafted decision is no longer the one the code
implements, the ADR is not accepted at all — it is superseded before acceptance by a new record that
says what was actually built. ADR 0013 is the worked example: drafted 2026-05-14, implemented within
weeks, accepted on 2026-08-23 once it was clear the pending refinement was waiting on a role this
repository does not have.

## Index

| ID | Title | Status |
|---|---|---|
| [0001](0001-monorepo.md) | Monorepo with folders per application/service | accepted |
| [0002](0002-multi-tenancy.md) | Multi-tenancy strategy: application filter in the MVP, RLS in production | accepted |
| [0003](0003-llm-output-schema.md) | LLM output via mandatory JSON Schema | accepted |
| [0004](0004-llm-provider-strategy.md) | LLM Provider strategy (agnostic, OpenAI as the default) | accepted |
| [0005](0005-productivity-scoring.md) | Meeting Productivity Score (opt-in, based on a declared goal) | accepted |
| [0006](0006-customer-confidence-and-account-health.md) | Customer Confidence (per meeting) and Account Health (aggregate) | accepted (partially superseded by 0015) |
| [0007](0007-iam-aws-style.md) | AWS-style IAM (Root + Users + Groups + Policies) | accepted |
| [0008](0008-desktop-tauri-sidecar.md) | Desktop App with Tauri 2 + Python Sidecar | accepted (Python sidecar superseded by 0035; Tauri 2 kept) |
| [0009](0009-azure-speech-credential-strategy.md) | Azure Speech credentials strategy | superseded by 0035 (the Azure Speech resource goes away via 0034) |
| [0010](0010-nlp-baseline-package.md) | Shared `nlp-baseline` package for PT-BR TF-IDF | accepted |
| [0011](0011-invite-flow-corporate-domain.md) | Invite-based onboarding with optional corporate domain restriction | accepted |
| [0012](0012-pii-person-name-strategy.md) | PII PERSON_NAME: regional BR strategy in the MVP, NER upgrade when internationalizing | accepted |
| [0013](0013-frontend-css-strategy.md) | Frontend CSS strategy (raw Tailwind, no shadcn, OKLCH tokens) | accepted (2026-08-23; drafted 2026-05-14, implemented long before it was accepted) |
| [0014](0014-defer-post-mvp-commercial-gate.md) | Defer post-MVP commercial gate (14 US deferred with a reactivation criterion) | superseded by 0038 (its gate was the FIAP pitch, held 2026-06-15) |
| [0015](0015-customer-confidence-minimal-persistence.md) | Customer Confidence — minimum viable persistence in Sub-phase 1.11 | accepted (partially supersedes 0006) |
| [0016](0016-production-readiness-checklist.md) | Production-readiness checklist and `rg-nora-prod` separation | partially superseded by 0034 (the Azure premises of Gaps 1/3/4/7 fall; Gaps 2 and 6 hold on a different substrate; Gap 5 delivered by 0029) |
| [0017](0017-license-agpl-3.md) | License: AGPL-3.0 | accepted |
| [0018](0018-test-coverage-targets.md) | Test coverage targets per critical area | accepted |
| [0019](0019-tenant-isolation-defense-in-depth.md) | Tenant isolation in depth: Postgres RLS + composite FK | accepted |
| [0020](0020-refresh-token-rotation.md) | Refresh token rotation + reuse detection (token families) | accepted |
| [0021](0021-soft-delete-strategy.md) | Soft-delete strategy on tenant-owned entities | accepted |
| [0022](0022-platform-control-plane-database.md) | Separate platform database + 2nd datasource (control plane) | accepted |
| [0023](0023-platform-operator-identity.md) | Operator identity (platform admin), separate from per-tenant IAM | accepted (Easy Auth superseded by 0025; edge changed by 0034) |
| [0024](0024-dynamic-model-catalog-and-modality-router.md) | Dynamic model catalog + router by modality + runtime resolution | accepted (extends 0004) |
| [0025](0025-operator-identity-v2-cloudflare-tunnel.md) | Operator identity v2: Cloudflare Tunnel + Access (supersedes Easy Auth from 0023) | accepted |
| [0026](0026-rls-complete-and-cutover.md) | Complete RLS, versioned role provisioning and enforce cutover | partially superseded by 0028 (enforce/cutover design; V019+R001 kept) |
| [0027](0027-branch-protection-and-required-ci.md) | `main` branch protection + mandatory CI gate | accepted |
| [0028](0028-rls-enforcement-auth-aware.md) | Auth-aware RLS enforcement: scope by data, Flyway-as-admin and cutover | accepted (fixes 0026) |
| [0029](0029-lgpd-operational.md) | Operational LGPD: right to be forgotten + retention (hard-delete) | accepted |
| [0030](0030-flows-event-bus-workflow-engine.md) | NORA Flows: in-process post-commit event bus + workflow engine | accepted |
| [0031](0031-oauth-integrations-token-storage.md) | OAuth integrations (Google) and token storage | accepted |
| [0032](0032-canvas-flows-react-flow.md) | NORA Flows canvas: React Flow styled with NORA tokens | accepted |
| [0033](0033-pii-chat-path-strategy.md) | PII strategy on the chat path (structured in the BFF + PERSON_NAME via the worker) | accepted |
| [0034](0034-azure-to-proxmox-migration.md) | Migration from Azure Container Apps to self-hosted Proxmox (single VM + Docker Compose) | accepted (supersedes 0009; partially supersedes 0016; extends 0025; substrate §1 and backup §9 superseded by 0036) |
| [0035](0035-local-stt-whisper-on-client.md) | Local STT: Whisper embedded in Tauri (Rust), on the client machine | superseded by 0039 (it supersedes 0009 and partially supersedes 0008; those parts stand) |
| [0036](0036-substrate-is-a-single-bare-metal-host.md) | The substrate is a single bare-metal Ubuntu host, not a Proxmox VM | superseded by 0051 (it supersedes 0034 §1 substrate and §9 backup; its single-host and Compose reasoning stand) |
| [0037](0037-ssh-over-the-existing-tunnel.md) | SSH reaches the host through the existing Cloudflare Tunnel, gated by Access | accepted (extends 0025 and 0034 §2) |
| [0038](0038-post-pitch-scope-realignment.md) | Post-pitch scope realignment | accepted (supersedes 0014) |
| [0039](0039-cloud-stt-openai-ephemeral-token.md) | Cloud STT: OpenAI transcription reached with an ephemeral session token | accepted (supersedes 0035) |
| [0040](0040-pii-scope-analysis-transcription-subprocessor.md) | The PII non-negotiable is scoped to analysis; transcription becomes a declared subprocessor | accepted (relates to 0012 and 0033; supersedes neither) |
| [0041](0041-nora-as-mcp-server.md) | NORA as an MCP server (the inbound path) | accepted (relates to 0031, the outbound path) |
| [0042](0042-web-unit-tests-vitest.md) | Vitest in `apps/web`: which parts of the first unit suite are a gate | accepted (delivers the runner 0018 asked for; does not enforce 0018's web threshold table) |
| [0043](0043-pii-address-coverage-and-a-decreasing-corpus-target.md) | ADDRESS becomes a redacted type, and the PII corpus gate becomes a decreasing target | accepted (extends 0012; pays the ADDRESS debt ADR 0040 recorded; supersedes neither) |
| [0044](0044-rag-index-backfill-operator-triggered.md) | The RAG index gets a backfill path, triggered by an operator | accepted (extends 0024; relates to 0012 and 0028) |
| [0045](0045-realtime-stt-session-contract.md) | The realtime STT session contract: one endpoint, one credential, no renewal loop | accepted (implements 0039; supersedes nothing) |
| [0046](0046-finish-the-declared-scope.md) | Finish the declared scope, and empty the limbo ADR 0038 left | accepted (extends 0038 §5; supersedes nothing) |
| [0047](0047-scheduled-flows-restricted-vocabulary.md) | Scheduled Flows: a restricted vocabulary, a claimed run, and a window that outlives a crash | accepted (successor record to 0030 §5; supersedes nothing) |
| [0048](0048-participant-identity-matching.md) | Participant identity: deterministic matching over the declared roster | accepted (implements US13 of 0046 §1; relates to 0012 and 0029; supersedes nothing) |
| [0049](0049-permission-boundaries.md) | Permission boundaries: a cap that never grants, and the four questions a cap raises | accepted (successor record to 0007; supersedes nothing) |
| [0050](0050-the-landing-page-states-what-the-code-does.md) | The public landing page states what the code does, and marks what it does not | accepted (lifts the DEC-04 freeze recorded in 0038, 0040 and 0046; supersedes nothing) |
| [0051](0051-the-substrate-is-an-azure-vm.md) | The substrate is an Azure VM, and Azure was never gone | accepted (supersedes 0036; corrects the "Azure is gone" overreach inherited from 0034) |

**50 numbered records, 45 of them `accepted`, measured 2026-08-23.** The other five are the ones
this index exists to keep straight: 0009, 0014 and 0035 are superseded outright, and 0016 and 0026
are **partially** superseded — their surviving halves still bind, so treating them as dead is as
wrong as treating them as whole. There is no `0000` and no gap: the sequence runs 0001–0050.

**Recount rather than copy.** `ls docs/adr/[0-9]*.md | wc -l` gives the total and the `Status:` line
of each file gives the split. Both numbers above were wrong somewhere in this repository on
2026-08-23 — the backlog carried "49 numbered ADRs, 36 of them accepted", where 36 was the figure
that fitted an earlier universe of 41 records and had simply been carried forward, inside a
paragraph whose stated purpose was to prove the figures had been recounted.
