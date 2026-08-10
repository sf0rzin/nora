# 0015 — Customer Confidence: minimum viable persistence in Sub-phase 1.11

- Status: accepted
- Date: 2026-05-14
- Partially supersedes: ADR 0006 (Customer Confidence + Account Health) — Account Health scope deferred

## Context

ADR 0006 (accepted on 2026-05-07) defines Customer Confidence (per meeting) and Account Health (aggregated). The full schema is in `docs/api/llm-schemas/meeting-analysis-v1.schema.json:117-167` and the Pydantic one in `services/nlp-worker/src/nora_nlp/models.py` — the worker ALREADY EMITS `customerConfidence` when the meeting is linked to a customer_account.

**But persistence was never implemented:**
- 0 Flyway migrations for `customer_accounts`, `customer_confidence_assessments`, `customer_buying_signals`, `customer_objections`, `meeting_account_links`, `account_health_snapshots`
- 0 REST endpoints exposing Customer Confidence
- 0 UI in MeetingDetail

Result: **every analysis generates Customer Confidence data that is discarded** when the backend deserializes the worker's response (the field is ignored).

### Narrative debt

NORA's public landing page (created in the Sub-phase 1.2 visual redesign v2) has a **HealthScoreSection** with a visual chart and meta cards (Account / Owner / Stage) and an events tooltip — it **sells the feature visually**. TOTVS demo:

1. The customer enters the public site, sees the Health Score chart
2. Clicks "Entrar", authenticates
3. Opens a meeting → **does not see the feature**

Credibility sinks in 5 seconds. **Narrative debt = the worst kind of debt.**

### Pre-Sub-phase 1.10 audit

The Design Architect's review (2026-05-14) re-severitized it from "Medium" to **"High"** with a vote to implement the minimum. The Tech Lead agreed in review (`50-coordenacao-arquitetos/2026-05-14-de-tech-lead-para-arquiteto-design-resposta-revisao-audit.md`).

## Decision

**Implement minimal Customer Confidence persistence in Sub-phase 1.11** with a deliberately reduced scope:

### Declared scope (Sub-phase 1.11)

1. **Migration V013** `customer_confidence_persistence`:
   - `customer_accounts` (id, tenant_id, name, owner_user_id, stage, created_at, updated_at). On-the-fly auto-creation by the name detected by the LLM
   - `meeting_account_links` (N:N meeting↔account — a meeting can have more than one account, rare but supported)
   - `customer_confidence_assessments` (id, tenant_id, meeting_id, customer_account_id, score, band, trend nullable, rationale, created_at) — 1:1 with (meeting, account)
   - `customer_buying_signals` (id, assessment_id, type enum, quote, weight nullable, position)
   - `customer_objections` (id, assessment_id, type enum, quote, severity, competitor nullable, position)

2. **Domain models** in `services/api/src/main/java/com/nora/api/domain/customer/`: `CustomerAccount`, `MeetingAccountLink`, `CustomerConfidenceAssessment`, `BuyingSignal`, `Objection`, enums

3. **Application service** `CustomerConfidenceService`: persists the assessment when the worker returns it, manages the auto-creation of `customer_accounts` by name

4. **Mapping in the worker proxy** `HttpNlpWorkerClient`: extracts `customerConfidence` from the worker's response and passes it to the service

5. **DTO + Response** `GET /meetings/{id}` expands the return with `customerConfidence` when present (nullable)

6. **UI** Design Architect: `CustomerConfidenceCard` in `MeetingDetail` (his scope)

### Scope **NOT included** (deferred via ADR 0014)

- **US50 Aggregated Account Health Score** — requires >5 meetings linked to an account to have a useful signal; waits for a real pilot
- **US51 Band-change alert** — depends on the Health Score
- **Manual CRUD of `customer_accounts`** — no Account Manager UI in the MVP
- **Dedicated endpoint** `GET /accounts/{id}/health` — does not exist yet

## Consequences

**Positive:**
- **Narrative debt resolved**: landing → demo → MeetingDetail show coherence. The customer who comes in sees what was promised
- **The existing LLM schema is leveraged**: the worker already emits it; only backend plumbing to persist it is needed
- **Account Health can evolve** later with real accumulated data (not under the MVP's shield)
- **MeetingDetail gains a valuable card** without needing a giant feature

**Negative:**
- Account Health not being implemented leaves US50-US51 open — explicitly deferred via ADR 0014
- Auto-creating `customer_accounts` by name is a heuristic — it may create duplicates (e.g., "Acme" and "ACME"). Mitigation: name normalization + dedup with `LOWER(name) = LOWER(:input)` + a future UI for manual merging

## Alternatives Considered

1. **(a) Implement the minimum viable** — **CHOSEN VOTE**. It balances narrative debt × manageable scope
2. **(b) Temporarily remove Customer Confidence from the landing page + a successor ADR to 0006 with "deferred, no timeline"** — rejected. Similar effort (~2h to remove the landing sections) but it loses a showcase feature; the landing becomes less persuasive
3. **(c) Keep the limbo (the schema exists, nothing persists)** — rejected explicitly by the Design Architect: "a bomb under the demo"

## Application Plan

Sub-phase 1.11 (Plan A Demo Polish), branch `feat/sub-1.11-customer-confidence-minimal`, effort **M (~6-8h agentic)**. Sequence:

1. Migration V013 + domain
2. JPA entities + repos
3. Service + worker proxy update
4. DTO + endpoint expansion
5. Integration tests (account auto-creation, persist assessment, GET returns confidence)
6. UI card (Design Architect in parallel)

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Joint (PO + Tech Lead + Design Architect) | ADR created. Tech Lead + Design vote for (a) implement the minimum; Stratfy (PO) confirmed as a block |
| 2026-05-21 | Stratfy (Anthony) | **Applied in PR #148** (accepted → implemented). Divergences from the plan: the migration shipped as **V017** (the V013 slot was used by soft-delete in #114) and the delivery came in 1 PR (not on the `feat/sub-1.11-...` branch). Trend is **authoritative on the server** (`CustomerConfidenceService.computeTrend`, band ±5), not the worker's guess. CI green w/ Testcontainers. |
