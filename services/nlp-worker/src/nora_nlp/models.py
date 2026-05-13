"""Modelos Pydantic que espelham os JSON Schemas em docs/api/llm-schemas/.

Qualquer mudanca de campo aqui exige bump da versao do schema correspondente.
"""

from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

# ---------- Enums alinhados aos schemas v1 ----------


class Severity(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Priority(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RiskCategory(str, Enum):
    COMPETITION = "COMPETITION"
    PRICE = "PRICE"
    CHURN = "CHURN"
    TIMELINE = "TIMELINE"
    TECHNICAL = "TECHNICAL"
    COMPLIANCE = "COMPLIANCE"
    OTHER = "OTHER"


class OpportunityCategory(str, Enum):
    UPSELL = "UPSELL"
    CROSS_SELL = "CROSS_SELL"
    REFERRAL = "REFERRAL"
    EXPANSION = "EXPANSION"
    OTHER = "OTHER"


class Sentiment(str, Enum):
    POSITIVE = "POSITIVE"
    NEUTRAL = "NEUTRAL"
    NEGATIVE = "NEGATIVE"
    MIXED = "MIXED"


class ProductivityBand(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class CoverageStatus(str, Enum):
    ADDRESSED = "ADDRESSED"
    PARTIAL = "PARTIAL"
    MISSED = "MISSED"


# ---------- Meeting Analysis v1 ----------


class Decision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: Annotated[str, Field(min_length=5, max_length=500)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]


class ActionItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: Annotated[str, Field(min_length=5, max_length=240)]
    assignee: str | None = Field(default=None, max_length=120)
    due_date: date | None = Field(default=None, alias="dueDate")
    priority: Priority
    source_quote: Annotated[str, Field(min_length=5, max_length=500, alias="sourceQuote")]


class Risk(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: Annotated[str, Field(min_length=5, max_length=500)]
    severity: Severity
    category: RiskCategory
    source_quote: Annotated[str, Field(min_length=5, max_length=500, alias="sourceQuote")]


class Opportunity(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: Annotated[str, Field(min_length=5, max_length=500)]
    estimated_value: Annotated[Severity, Field(alias="estimatedValue")]
    category: OpportunityCategory
    source_quote: Annotated[str, Field(min_length=5, max_length=500, alias="sourceQuote")]


class Participant(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: Annotated[str, Field(min_length=2, max_length=120)]
    role: str | None = Field(default=None, max_length=120)
    mention_count: Annotated[int, Field(ge=1, alias="mentionCount")]


class ProductivityCoverage(BaseModel):
    """Status de cobertura de um outcome esperado pela reunião."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    expected_outcome: Annotated[str, Field(min_length=3, max_length=240, alias="expectedOutcome")]
    status: CoverageStatus
    evidence: str | None = Field(default=None, max_length=500)


class ProductivityAssessment(BaseModel):
    """Productivity Score da reunião (ADR 0005). Opt-in por reunião."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    score: Annotated[int, Field(ge=0, le=100)]
    band: ProductivityBand
    coverage: list[ProductivityCoverage] = Field(default_factory=list)
    off_topic_ratio: float | None = Field(default=None, ge=0.0, le=1.0, alias="offTopicRatio")
    decision_density: float | None = Field(default=None, ge=0.0, le=1.0, alias="decisionDensity")
    rationale: Annotated[str, Field(min_length=10, max_length=1000)]


class BaselineTerm(BaseModel):
    """Termo extraido pelo baseline TF-IDF (pre-LLM, interpretavel).

    Espelha o resultado de ``nlp_baseline.TfidfBaseline.top_terms_per_doc`` --- o
    ``score`` eh o valor TF-IDF normalizado pelo proprio sklearn (sub-linear TF
    com norma L2 por documento), entao costuma ficar no intervalo [0, 1]. Vide
    decisao em ADR 0010.
    """

    model_config = ConfigDict(extra="forbid")

    term: Annotated[str, Field(min_length=1, max_length=120)]
    score: Annotated[float, Field(ge=0.0, le=1.0)]


class MeetingAnalysisV1(BaseModel):
    """Espelho de docs/api/llm-schemas/meeting-analysis-v1.schema.json."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    summary: Annotated[str, Field(min_length=30, max_length=2000)]
    decisions: list[Decision] = Field(default_factory=list, max_length=20)
    action_items: list[ActionItem] = Field(default_factory=list, alias="actionItems", max_length=30)
    risks: list[Risk] = Field(default_factory=list, max_length=20)
    opportunities: list[Opportunity] = Field(default_factory=list, max_length=20)
    sentiment_overall: Sentiment = Field(alias="sentimentOverall")
    topics: list[Annotated[str, Field(min_length=2, max_length=60)]] = Field(
        default_factory=list, max_length=12
    )
    participants: list[Participant] = Field(default_factory=list, max_length=30)
    # Termos do baseline TF-IDF. Opcional --- preenchido pelo worker apos PII
    # redaction e antes do LLM. Default vazio mantem o contrato existente: o
    # LLM continua sendo a fonte de todos os outros campos e nao precisa
    # produzir este. Vide ADR 0010.
    baseline_terms: list[BaselineTerm] = Field(
        default_factory=list, alias="baselineTerms", max_length=50
    )
    # Productivity Score opt-in (ADR 0005). None quando o usuario nao declarou
    # objetivo/outcomes esperados. Quando presente, eh gerado pelo LLM com base
    # no goal injetado no prompt.
    productivity: ProductivityAssessment | None = Field(default=None)


# ---------- PII Redaction v1 ----------


class PiiType(str, Enum):
    EMAIL = "EMAIL"
    PHONE = "PHONE"
    CPF = "CPF"
    CNPJ = "CNPJ"
    CREDIT_CARD = "CREDIT_CARD"
    PERSON_NAME = "PERSON_NAME"
    ADDRESS = "ADDRESS"
    OTHER = "OTHER"


class Redaction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    placeholder: Annotated[str, Field(pattern=r"^\[\[[A-Z_]+_[0-9]+\]\]$")]
    type: PiiType
    original_hash: str | None = Field(default=None, alias="originalHash")


class PiiRedactionV1(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    redacted_text: str = Field(alias="redactedText")
    redactions: list[Redaction] = Field(default_factory=list)


# ---------- Worker Analyze (request/response do endpoint interno) ----------


class TenantProduct(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    description: str | None = None
    key_differentiators: list[str] = Field(default_factory=list, alias="keyDifferentiators")


class GlossaryEntry(BaseModel):
    model_config = ConfigDict(extra="forbid")

    term: str
    meaning: str


class TenantContext(BaseModel):
    """Subset do contexto do tenant enviado pelo backend ao worker."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    company_name: str = Field(alias="companyName")
    industry: str | None = None
    value_proposition: str = Field(alias="valueProposition")
    products: list[TenantProduct] = Field(default_factory=list)
    competitors: list[str] = Field(default_factory=list)
    ideal_customer_profile: str | None = Field(default=None, alias="idealCustomerProfile")
    objection_handling: list[str] = Field(default_factory=list, alias="objectionHandling")
    glossary: list[GlossaryEntry] = Field(default_factory=list)


class AnalyzeOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")

    include_risks: bool = Field(default=True, alias="includeRisks")
    include_opportunities: bool = Field(default=True, alias="includeOpportunities")
    max_action_items: int = Field(default=20, ge=1, le=50, alias="maxActionItems")
    prompt_version: str = Field(default="meeting-analysis-v1", alias="promptVersion")


class MeetingGoal(BaseModel):
    """Input opt-in declarado pelo usuario pra calculo do Productivity Score (ADR 0005).

    Sem MeetingGoal no request, o productivity da resposta vem None.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    purpose: Annotated[str, Field(min_length=3, max_length=500)]
    expected_outcomes: list[Annotated[str, Field(min_length=3, max_length=240)]] = Field(
        alias="expectedOutcomes",
        min_length=1,
        max_length=10,
    )
    project_state_snapshot: str | None = Field(
        default=None,
        max_length=2000,
        alias="projectStateSnapshot",
    )


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    meeting_id: str = Field(alias="meetingId")
    tenant_id: str = Field(alias="tenantId")
    language: str = "pt-BR"
    transcript: Annotated[str, Field(min_length=1)]
    tenant_context: TenantContext = Field(alias="tenantContext")
    options: AnalyzeOptions = Field(default_factory=AnalyzeOptions)
    # Goal opt-in pra Productivity Score. None desabilita o calculo.
    goal: MeetingGoal | None = Field(default=None)


class AnalyzeMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    model_version: str = Field(alias="modelVersion")
    prompt_version: str = Field(alias="promptVersion")
    tokens_input: int = Field(default=0, alias="tokensInput")
    tokens_output: int = Field(default=0, alias="tokensOutput")
    processing_millis: int = Field(default=0, alias="processingMillis")
    pii_redactions_applied: int = Field(default=0, alias="piiRedactionsApplied")


class AnalyzeResponse(MeetingAnalysisV1):
    """Response combina o schema canonico + metadados de execucao."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    meeting_id: str = Field(alias="meetingId")
    metadata: AnalyzeMetadata
