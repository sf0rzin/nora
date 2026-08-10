# 0018 — Test coverage targets per critical area

- Status: accepted
- Date: 2026-05-14

## Context

The pre-Sub-phase 1.10 audit included **§12 — Test Coverage** with real measurements:

### NLP Worker (Python — pytest + pytest-cov)
- **87% total coverage** (54 tests, 18.85s)
- Critical areas >90%: `models.py` 100%, `pii_shield.py` 95%, `stub_analyzer.py` 91%, `baseline.py` 94%, `stub_live_analyzer.py` 95%, `llm_analyzer.py` 87%
- Low areas: `live_analyzer.py` 30%, `clients/llm.py` 47%, `routers/analyze.py` 58% (all of them have stubs in CI by design — real LLM calls do not run in CI)

### Spring Backend (Java — JUnit 5 + JaCoCo)
- **67% instruction coverage** (174 tests; 53% branch coverage)
- Critical areas >90%:
  - `InvitationService` 98.1%
  - `PolicyEvaluator` 95.8% (84% branches)
  - `AuthService` 93.2%
  - `AuthorizationService` 89.9% (100% branches)
- Low areas: `tenant` 40.4% (TenantContextController), `analysis` 47% (AnalysisService), `exception` 53.7% (GlobalExceptionHandler)

### Next.js Web
- **0% effective coverage** — `apps/web/package.json` has no `test` script and no runner (vitest/jest/playwright). No suite

### Python Desktop sidecar
- Partially covered in `apps/desktop/sidecar/tests/test_protocol.py` (NDJSON Rust↔Python contract). Coverage not measured in this audit (the Desktop friend's scope)

## Decision

Establish **coverage targets per area** that **must be maintained** (not regress):

### Spring Backend

| Tier | Minimum coverage |
|---|---|
| **Critical areas** (IAM, Auth, PII, Speech, Analysis pipeline) | **>85% instruction sustained** |
| Non-critical areas (REST controllers without complex logic, DTOs, mappers) | **>60% instruction sustained** |
| Backend branch coverage (overall) | **>70% sustained** (today 53%, a gap to close in Sub-phase 1.12) |

**Specific classes marked as "critical"** (a regression below 85% blocks the merge):
- `PolicyEvaluator`
- `AuthorizationService`
- `AuthService`
- `InvitationService`
- `RefreshTokenService` (created in Sub-phase 1.3)
- `PiiShield` (in the backend, if there is one — currently it is only in the worker)
- `SpeechTokenService` (token broker — ADR 0009)
- `AnalysisService` (worker proxy)
- `MeetingGoalService` + `ProductivityAssessmentService` (created in 1.8, close to 1.11 Customer Confidence)

### NLP Worker

| Tier | Minimum coverage |
|---|---|
| Total | **>85% sustained** (today 87%) |
| `pii_shield.py` | **>90%** (today 95%) |
| `models.py` | **100%** (today 100%) — all Pydantic models exercised |

### Next.js Web (from Sub-phase 1.12 onwards)

| Tier | Minimum coverage |
|---|---|
| Auth flow pages | **>50% sustained** |
| Dashboard + MeetingDetail + Tasks pages | **>40% sustained** |
| Shared components (PolicyEditor, ProductivityScoreCard, CustomerConfidenceCard) | **>60% sustained** |

Sub-phase 1.12 adds Vitest as the runner + initial suites.

### Desktop

Targets remain the **Desktop friend's scope**. The Tech Lead does not dictate; he only recommends in the coordination briefing.

## How to measure

### Backend
```bash
cd services/api
mvn -B test jacoco:report
# relatório em target/site/jacoco/index.html
# CSV agregável em target/site/jacoco/jacoco.csv
```

CI: the `ci.yml` job `api` already runs `mvn -B verify`, which includes JaCoCo. Minimum threshold configured in `pom.xml` (Sub-phase 1.12 adds the `jacoco-maven-plugin` `check` goal with rules).

### Worker
```bash
cd services/nlp-worker
pip install pytest-cov  # adicionar a [dev] em pyproject.toml
python -m pytest --cov=src --cov-report=term --cov-report=xml --cov-fail-under=85
```

CI: the `ci.yml` job `worker` will run with `--cov-fail-under=85` (Sub-phase 1.12 adds it).

### Web
Sub-phase 1.12: add Vitest + `npm test -- --coverage` to the `ci.yml` job `web`.

## Consequences

**Positive:**
- Critical areas (IAM, Auth, PII) have a safety net against regression
- A technical reviewer (TOTVS recruiter, code walkthrough) sees concrete, non-improvised numbers
- Coverage targets become an **explicit conversation** in PR review ("this change dropped PolicyEvaluator to 84%, is that OK or do we add a test?")

**Negative:**
- Low areas (tenant 40%, analysis 47%) **need attention** in Sub-phase 1.12 before raising the overall bar
- The web without a runner requires investment in Vitest + suites (~1 agentic day) — it goes into 1.12

## Alternatives Considered

1. **Mandatory >80% total coverage (no distinction by area)** — rejected. Forcing 80% on boilerplate controllers produces valueless tests (testing getter/setter). Differentiation by criticality is more useful
2. **Coverage as an optional-only gate, with no CI failure** — rejected. Without enforcement, critical areas regress silently
3. **Mutation testing (PIT)** — more rigorous than line coverage but high overhead. Added to the Sub-phase 1.13+ agenda if commercial traction justifies the investment

## Application Plan

- **Sub-phase 1.10 (this ADR)**: targets declared, with no automatic enforcement yet
- **Sub-phase 1.12** (Production Hardening):
  - JaCoCo `check` goal with critical rules
  - pytest-cov `--cov-fail-under=85` in CI
  - Vitest added + web thresholds
  - PR template with the checkbox "coverage maintained or increased in the areas declared critical"

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Tech Lead | ADR created during Sub-phase 1.10 after audit §12 measured real coverage. Enforcement in Sub-phase 1.12 |
