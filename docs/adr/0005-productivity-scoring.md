# 0005 — Meeting Productivity Score (opt-in, based on a declared goal)

- Status: accepted
- Date: 2026-05-07

## Context

In a team brainstorm the idea came up of measuring how productive a meeting was. Assessing productivity "in a vacuum" (from the transcript content alone) produces arbitrary results — any meeting can look productive or unproductive depending on the reader's taste. The team refined the idea into an **opt-in** model in which the user declares the meeting's goal, turning the assessment into something verifiable.

Integration with external sources of "project state" (Jira, Linear, Azure DevOps, GitHub Projects) is a natural path, but it requires MCPs and per-tenant authentication — out of scope for the MVP.

## Decision

Add an optional **Productivity Score** feature with the following properties:

1. **Opt-in per meeting.** With no declared goal, NORA does not attempt to assess productivity.
2. **User input** when uploading the meeting (or when editing the meeting before reprocessing):
   - `purpose` (free text): "Refinement of epic X"
   - `expectedOutcomes` (list of strings): points that needed to be addressed/decided
   - `projectStateSnapshot` (optional text): "what is done" — manual in the MVP; via MCP post-MVP
3. **Worker output**, inside `meeting-analysis-v1.schema.json`:
   ```json
   "productivity": {
     "score": 78,
     "band": "HIGH",
     "coverage": [
       { "expectedOutcome": "Definir critérios de aceite da feature X", "status": "ADDRESSED", "evidence": "..." },
       { "expectedOutcome": "Decidir provider de pagamento", "status": "PARTIAL", "evidence": "..." }
     ],
     "offTopicRatio": 0.18,
     "decisionDensity": 0.6,
     "rationale": "..."
   }
   ```
4. **Score formula (v1):** a weighted combination of:
   - Coverage of expected outcomes (dominant weight): `% ADDRESSED + 0.5 × % PARTIAL`.
   - Decision density (decisions per minute, normalized).
   - Penalty for a high `offTopicRatio`.
   - Bonus for generating concrete action items with a defined owner.
5. **Band derived** from the score: `LOW` (<40), `MEDIUM` (40–69), `HIGH` (≥70). Tunable via worker configuration, not hardcoded in the domain.
6. **Absence of outcomes** = do not emit `productivity` in the payload (the field is nullable). Do not invent a score without an answer key.

**Persistence:** see `docs/data-model.md` §2.19–§2.21 (`meeting_goals`, `meeting_productivity_assessments`, `meeting_outcome_coverage`).

**Tier:** Core and Enterprise (there is no reason to limit it to Enterprise — Core also gains value).

## Consequences

**Positive:**

- The assessment becomes **verifiable**: the LLM compares what happened with what the user asked to happen.
- It creates a natural hook for a future project-state MCP integration (US47), with structured data.
- It works in the MVP already, with no dependency on an external integration.

**Negative:**

- It adds input fields to the upload and a new display UI. A small increase in surface area.
- A score computed by an LLM has variance — it needs a deterministic prompt (low temperature, few-shot examples) and ideally periodic human validation with the first tenants.
- When processing with a declared `goal`, the prompt gets larger → slightly higher cost per analysis.

## Alternatives Considered

1. **Score without a declared goal.** Rejected: subjective, indefensible.
2. **Qualitative only (Low/Medium/High with no number).** Rejected: users want to compare meetings; a number helps dashboards and trends.
3. **Productivity Score always on.** Rejected: it forces the user to declare a goal even when they just want a quick summary.

## Accompanying Rules

- The feature is strictly opt-in. The UI must make it clear that without `expectedOutcomes` the score will not be generated.
- Never use the score as a metric for evaluating people — document this in the UI itself ("an indicator of the meeting, not of the participants").
- `projectStateSnapshot` follows the PII Shield: redaction before sending to the LLM.
- The MCP integration (US47) enters the backlog as `Won't Have v1`; a new ADR will cover the design of the `nora-mcp-projectstate` MCP when the time comes.
