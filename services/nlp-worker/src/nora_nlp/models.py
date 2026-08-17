"""Pydantic models that mirror the JSON Schemas in docs/api/llm-schemas/.

Any field change here requires bumping the version of the corresponding schema.
"""

from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

# ---------- Enums aligned to the v1 schemas ----------


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


class ConfidenceBand(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class ConfidenceTrend(str, Enum):
    IMPROVING = "IMPROVING"
    STABLE = "STABLE"
    DECLINING = "DECLINING"


class BuyingSignalType(str, Enum):
    BUDGET_DISCUSSED = "BUDGET_DISCUSSED"
    TIMELINE_DISCUSSED = "TIMELINE_DISCUSSED"
    STAKEHOLDER_INVOLVED = "STAKEHOLDER_INVOLVED"
    NEXT_STEP_REQUESTED = "NEXT_STEP_REQUESTED"
    REFERENCE_REQUESTED = "REFERENCE_REQUESTED"
    PROPOSAL_REQUESTED = "PROPOSAL_REQUESTED"
    OTHER = "OTHER"


class ObjectionType(str, Enum):
    PRICE = "PRICE"
    TIMELINE = "TIMELINE"
    AUTHORITY = "AUTHORITY"
    NEED = "NEED"
    COMPETITOR_MENTION = "COMPETITOR_MENTION"
    TRUST = "TRUST"
    FEATURE_GAP = "FEATURE_GAP"
    OTHER = "OTHER"


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
    """A participant as the MODEL saw them, which is not a person (US13, ADR 0048).

    `routers/analyze.py` redacts before the analyzer runs, so `name` is normally a
    `[[PERSON_NAME_n]]` placeholder --- the prompt says so explicitly (item 12 of
    `prompts/meeting-analysis-v1.md`). And `_redact_person_names` numbers EVERY occurrence
    separately, so two mentions of one name are two different placeholders: nothing on this
    side of the shield can decide that two entries denote the same person, whatever
    algorithm it uses.

    This array is also consumed by nobody --- `WorkerDtos.AnalyzeResponse` in `services/api`
    has no `participants` field. Deduplication and matching happen in the API, over the
    roster the user declared on upload. Kept rather than removed because the field is in the
    published schema and in the strict JSON Schema sent to the provider; retiring it is a
    contract change for whoever next versions `meeting-analysis-v1`.
    """

    model_config = ConfigDict(extra="forbid")

    name: Annotated[str, Field(min_length=2, max_length=120)]
    role: str | None = Field(default=None, max_length=120)
    mention_count: Annotated[int, Field(ge=1, alias="mentionCount")]


class ProductivityCoverage(BaseModel):
    """Coverage status of an outcome expected from the meeting."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    expected_outcome: Annotated[str, Field(min_length=3, max_length=240, alias="expectedOutcome")]
    status: CoverageStatus
    evidence: str | None = Field(default=None, max_length=500)


class ProductivityAssessment(BaseModel):
    """Productivity Score of the meeting (ADR 0005). Opt-in per meeting."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    score: Annotated[int, Field(ge=0, le=100)]
    band: ProductivityBand
    coverage: list[ProductivityCoverage] = Field(default_factory=list)
    off_topic_ratio: float | None = Field(default=None, ge=0.0, le=1.0, alias="offTopicRatio")
    decision_density: float | None = Field(default=None, ge=0.0, le=1.0, alias="decisionDensity")
    rationale: Annotated[str, Field(min_length=10, max_length=1000)]


class BuyingSignal(BaseModel):
    """Buying signal detected in the conversation with a customer/lead (ADR 0006)."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    type: BuyingSignalType
    quote: Annotated[str, Field(min_length=5, max_length=500)]
    weight: float | None = Field(default=None, ge=0.0, le=1.0)


class Objection(BaseModel):
    """Objection raised by the customer/lead in the conversation (ADR 0006)."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    type: ObjectionType
    quote: Annotated[str, Field(min_length=5, max_length=500)]
    severity: Severity
    competitor: str | None = Field(default=None, max_length=120)


class CustomerConfidence(BaseModel):
    """Customer Confidence of the meeting (ADR 0006). The LLM emits it only for
    external conversations (customer/lead/sales); None for internal meetings.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    score: Annotated[int, Field(ge=0, le=100)]
    band: ConfidenceBand
    trend: ConfidenceTrend | None = Field(default=None)
    account_name: str | None = Field(default=None, max_length=120, alias="accountName")
    buying_signals: list[BuyingSignal] = Field(
        default_factory=list, alias="buyingSignals", max_length=20
    )
    objections: list[Objection] = Field(default_factory=list, max_length=20)
    rationale: Annotated[str, Field(min_length=10, max_length=1000)]


class BaselineTerm(BaseModel):
    """Term extracted by the TF-IDF baseline (pre-LLM, interpretable).

    Mirrors the result of ``nlp_baseline.TfidfBaseline.top_terms_per_doc`` --- the
    ``score`` is the TF-IDF value normalized by sklearn itself (sub-linear TF
    with L2 norm per document), so it usually lands in the [0, 1] range. See the
    decision in ADR 0010.
    """

    model_config = ConfigDict(extra="forbid")

    term: Annotated[str, Field(min_length=1, max_length=120)]
    score: Annotated[float, Field(ge=0.0, le=1.0)]


class MeetingAnalysisV1(BaseModel):
    """Mirror of docs/api/llm-schemas/meeting-analysis-v1.schema.json."""

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
    # Terms from the TF-IDF baseline. Optional --- filled by the worker after PII
    # redaction and before the LLM. An empty default keeps the existing contract: the
    # LLM remains the source of all the other fields and does not need to
    # produce this one. See ADR 0010.
    baseline_terms: list[BaselineTerm] = Field(
        default_factory=list, alias="baselineTerms", max_length=50
    )
    # Opt-in Productivity Score (ADR 0005). None when the user did not declare
    # a goal/expected outcomes. When present, it is generated by the LLM based
    # on the goal injected into the prompt.
    productivity: ProductivityAssessment | None = Field(default=None)
    # Customer Confidence (ADR 0006 / ADR 0015). None for internal meetings;
    # populated by the LLM when the meeting is a customer/lead/sales conversation.
    # The decision to emit vs null is the LLM's own (no Python-side gating); the
    # accountName is the customer/company name detected in the transcript.
    customer_confidence: CustomerConfidence | None = Field(default=None, alias="customerConfidence")


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


# ---------- Worker Analyze (request/response of the internal endpoint) ----------


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
    """Subset of the tenant context sent by the backend to the worker."""

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
    """Opt-in input declared by the user for computing the Productivity Score (ADR 0005).

    Without a MeetingGoal in the request, the response's productivity comes back None.
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
    # Defensive cap: 1MB of text (~250k tokens) — beyond gpt-4o-mini's context
    # window (128k tokens). An 8h meeting transcribed fits comfortably.
    transcript: Annotated[str, Field(min_length=1, max_length=1_000_000)]
    tenant_context: TenantContext = Field(alias="tenantContext")
    options: AnalyzeOptions = Field(default_factory=AnalyzeOptions)
    # Opt-in goal for the Productivity Score. None disables the computation.
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
    """Response combines the canonical schema + execution metadata."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    meeting_id: str = Field(alias="meetingId")
    metadata: AnalyzeMetadata


# ---------- Worker Split (meeting boundary detection in a single file) ----------


class SplitRequest(BaseModel):
    """Request of the /split endpoint: .txt file with 1..N concatenated meetings."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    # Same defensive cap as /analyze: 1MB of text.
    transcript: Annotated[str, Field(min_length=1, max_length=1_000_000)]
    language: str = "pt-BR"


class SplitSegment(BaseModel):
    """One meeting detected inside the file. Lines are 1-based and inclusive.

    `preview` carries the first ~200 chars of the segment ALREADY REDACTED by the
    PII Shield (never raw PII — ADR 0012). The real slicing is client-side
    using `startLine`/`endLine` over the original file.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    index: Annotated[int, Field(ge=1)]
    title: Annotated[str, Field(min_length=1, max_length=120)]
    start_line: Annotated[int, Field(ge=1, alias="startLine")]
    end_line: Annotated[int, Field(ge=1, alias="endLine")]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    preview: Annotated[str, Field(max_length=240)]


class SplitMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    model_version: str = Field(alias="modelVersion")
    prompt_version: str = Field(alias="promptVersion")
    tokens_input: int = Field(default=0, alias="tokensInput")
    tokens_output: int = Field(default=0, alias="tokensOutput")
    processing_millis: int = Field(default=0, alias="processingMillis")
    pii_redactions_applied: int = Field(default=0, alias="piiRedactionsApplied")


class SplitResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    segments: list[SplitSegment]
    total_lines: Annotated[int, Field(ge=1, alias="totalLines")]
    metadata: SplitMetadata


# ---------- Live Highlights (real-time analysis during the meeting) ----------


class LiveHighlightItem(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    text: Annotated[str, Field(min_length=3, max_length=500)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    source_quote: Annotated[str, Field(min_length=3, max_length=500, alias="sourceQuote")]


class LiveTaskItem(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    title: Annotated[str, Field(min_length=3, max_length=240)]
    assignee: str | None = Field(default=None, max_length=120)
    priority: Priority = Priority.MEDIUM
    source_quote: Annotated[str, Field(min_length=3, max_length=500, alias="sourceQuote")]


class LiveHighlightsV1(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    decisions: list[LiveHighlightItem] = Field(default_factory=list, max_length=20)
    next_steps: list[LiveHighlightItem] = Field(
        default_factory=list, alias="nextSteps", max_length=20
    )
    observations: list[LiveHighlightItem] = Field(default_factory=list, max_length=20)
    tasks: list[LiveTaskItem] = Field(default_factory=list, max_length=30)


class LiveAnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    # Cap at 500KB per chunk: live chunks are typically 1-2min of transcribed
    # speech (~3-5KB). 500KB covers extreme scenarios without becoming an LLM
    # cost vector via a giant prompt.
    transcript_chunk: Annotated[
        str, Field(min_length=20, max_length=500_000, alias="transcriptChunk")
    ]
    previous_highlights: LiveHighlightsV1 | None = Field(default=None, alias="previousHighlights")
    language: str = "pt-BR"


class LiveAnalyzeMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    processing_millis: int = Field(default=0, alias="processingMillis")
    tokens_input: int = Field(default=0, alias="tokensInput")
    tokens_output: int = Field(default=0, alias="tokensOutput")
    pii_redactions_applied: int = Field(default=0, alias="piiRedactionsApplied")
    model_version: str = Field(default="", alias="modelVersion")


class LiveAnalyzeResponse(LiveHighlightsV1):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    metadata: LiveAnalyzeMetadata
