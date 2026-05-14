# 0018 — Test coverage targets por área crítica

- Status: aceito
- Data: 2026-05-14
- Decisores: Tech Lead

## Contexto

Audit pré-Sub-fase 1.10 incluiu **§12 — Test Coverage** com medições reais:

### Worker NLP (Python — pytest + pytest-cov)
- **87% coverage total** (54 tests, 18.85s)
- Áreas críticas >90%: `models.py` 100%, `pii_shield.py` 95%, `stub_analyzer.py` 91%, `baseline.py` 94%, `stub_live_analyzer.py` 95%, `llm_analyzer.py` 87%
- Áreas baixas: `live_analyzer.py` 30%, `clients/llm.py` 47%, `routers/analyze.py` 58% (todas tem stubs em CI por design — chamadas LLM reais não rodam em CI)

### Backend Spring (Java — JUnit 5 + JaCoCo)
- **67% instruction coverage** (174 tests; 53% branch coverage)
- Áreas críticas >90%:
  - `InvitationService` 98.1%
  - `PolicyEvaluator` 95.8% (84% branches)
  - `AuthService` 93.2%
  - `AuthorizationService` 89.9% (100% branches)
- Áreas baixas: `tenant` 40.4% (TenantContextController), `analysis` 47% (AnalysisService), `exception` 53.7% (GlobalExceptionHandler)

### Web Next.js
- **0% coverage efetivo** — `apps/web/package.json` não tem `test` script nem runner (vitest/jest/playwright). Sem suite

### Desktop sidecar Python
- Coberto parcialmente em `apps/desktop/sidecar/tests/test_protocol.py` (NDJSON Rust↔Python contract). Coverage não medido neste audit (escopo do amigo Desktop)

## Decisão

Estabelecer **targets de coverage por área** que **devem ser mantidos** (não regredir):

### Backend Spring

| Tier | Cobertura mínima |
|---|---|
| **Áreas críticas** (IAM, Auth, PII, Speech, Analysis pipeline) | **>85% instruction sustained** |
| Áreas não-críticas (controllers REST sem lógica complexa, DTOs, mappers) | **>60% instruction sustained** |
| Branch coverage backend (geral) | **>70% sustained** (hoje 53%, gap a fechar na Sub-fase 1.12) |

**Classes específicas marcadas "críticas"** (regressão abaixo de 85% bloqueia merge):
- `PolicyEvaluator`
- `AuthorizationService`
- `AuthService`
- `InvitationService`
- `RefreshTokenService` (criada na Sub-fase 1.3)
- `PiiShield` (no backend, se houver — atualmente está só no worker)
- `SpeechTokenService` (token broker — ADR 0009)
- `AnalysisService` (worker proxy)
- `MeetingGoalService` + `ProductivityAssessmentService` (criados na 1.8, próximo da 1.11 Customer Confidence)

### Worker NLP

| Tier | Cobertura mínima |
|---|---|
| Total | **>85% sustained** (hoje 87%) |
| `pii_shield.py` | **>90%** (hoje 95%) |
| `models.py` | **100%** (hoje 100%) — todos Pydantic models exercitados |

### Web Next.js (a partir da Sub-fase 1.12)

| Tier | Cobertura mínima |
|---|---|
| Auth flow pages | **>50% sustained** |
| Dashboard + MeetingDetail + Tasks pages | **>40% sustained** |
| Components compartilhados (PolicyEditor, ProductivityScoreCard, CustomerConfidenceCard) | **>60% sustained** |

Sub-fase 1.12 adiciona Vitest como runner + suites iniciais.

### Desktop

Targets ficam **escopo do amigo Desktop**. Tech Lead não dita; apenas recomenda em briefing de coordenação.

## Como medir

### Backend
```bash
cd services/api
mvn -B test jacoco:report
# relatório em target/site/jacoco/index.html
# CSV agregável em target/site/jacoco/jacoco.csv
```

CI: `ci.yml` job `api` já roda `mvn -B verify` que inclui JaCoCo. Threshold mínimo configurado no `pom.xml` (Sub-fase 1.12 adiciona `jacoco-maven-plugin` `check` goal com regras).

### Worker
```bash
cd services/nlp-worker
pip install pytest-cov  # adicionar a [dev] em pyproject.toml
python -m pytest --cov=src --cov-report=term --cov-report=xml --cov-fail-under=85
```

CI: `ci.yml` job `worker` rodará com `--cov-fail-under=85` (Sub-fase 1.12 adiciona).

### Web
Sub-fase 1.12: adicionar Vitest + `npm test -- --coverage` no `ci.yml` job `web`.

## Consequências

**Positivas:**
- Áreas críticas (IAM, Auth, PII) têm rede de segurança contra regressão
- Reviewer técnico (recrutador TOTVS, code walkthrough) vê números concretos não-improvisados
- Coverage targets viram **conversa explícita** em PR review ("essa mudança baixou PolicyEvaluator pra 84%, OK ou adicionamos teste?")

**Negativas:**
- Áreas baixas (tenant 40%, analysis 47%) **precisam atenção** na Sub-fase 1.12 antes de subir a barra geral
- Web sem runner exige investimento em Vitest + suites (~1 dia agentic) — entra na 1.12

## Alternativas Consideradas

1. **Coverage total >80% obrigatório (sem distinção por área)** — rejeitado. Forçar 80% em controllers boilerplate gera testes sem valor (testando getter/setter). Diferenciação por criticidade é mais útil
2. **Coverage como gate apenas opcional, sem fail no CI** — rejeitado. Sem enforcement, áreas críticas regridem silenciosamente
3. **Mutation testing (PIT)** — mais rigoroso que line coverage mas overhead alto. Adicionado na agenda da Sub-fase 1.13+ se tração comercial justificar investimento

## Plano de Aplicação

- **Sub-fase 1.10 (este ADR)**: targets declarados, sem enforcement automático ainda
- **Sub-fase 1.12** (Production Hardening):
  - JaCoCo `check` goal com regras críticas
  - pytest-cov `--cov-fail-under=85` em CI
  - Vitest adicionado + thresholds web
  - PR template com checkbox "coverage mantido ou subiu nas áreas declaradas críticas"

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Tech Lead | ADR criado durante Sub-fase 1.10 após audit §12 medir coverage real. Enforcement na Sub-fase 1.12 |
