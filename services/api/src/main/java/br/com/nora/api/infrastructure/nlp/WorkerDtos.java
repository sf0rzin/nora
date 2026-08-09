package br.com.nora.api.infrastructure.nlp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * Transport DTOs for the NLP worker. Only these objects are serialized/deserialized at the HTTP
 * boundary; they do not leak into the rest of the app.
 */
public final class WorkerDtos {

    private WorkerDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyzeRequest(
            @JsonProperty("meetingId") String meetingId,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("language") String language,
            @JsonProperty("transcript") String transcript,
            @JsonProperty("tenantContext") TenantContext tenantContext,
            @JsonProperty("options") Options options,
            @JsonProperty("goal") Goal goal) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Goal(
            @JsonProperty("purpose") String purpose,
            @JsonProperty("expectedOutcomes") List<String> expectedOutcomes,
            @JsonProperty("projectStateSnapshot") String projectStateSnapshot) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TenantContext(
            @JsonProperty("companyName") String companyName,
            @JsonProperty("industry") String industry,
            @JsonProperty("valueProposition") String valueProposition,
            @JsonProperty("products") List<Product> products,
            @JsonProperty("competitors") List<String> competitors,
            @JsonProperty("idealCustomerProfile") String idealCustomerProfile,
            @JsonProperty("objectionHandling") List<String> objectionHandling,
            @JsonProperty("glossary") List<GlossaryEntry> glossary) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("keyDifferentiators") List<String> keyDifferentiators) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GlossaryEntry(
            @JsonProperty("term") String term, @JsonProperty("meaning") String meaning) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Options(
            @JsonProperty("includeRisks") Boolean includeRisks,
            @JsonProperty("includeOpportunities") Boolean includeOpportunities,
            @JsonProperty("maxActionItems") Integer maxActionItems,
            @JsonProperty("promptVersion") String promptVersion) {}

    public record AnalyzeResponse(
            @JsonProperty("meetingId") String meetingId,
            @JsonProperty("summary") String summary,
            @JsonProperty("sentimentOverall") String sentimentOverall,
            @JsonProperty("topics") List<String> topics,
            @JsonProperty("decisions") List<DecisionDto> decisions,
            @JsonProperty("actionItems") List<ActionItemDto> actionItems,
            @JsonProperty("risks") List<RiskDto> risks,
            @JsonProperty("opportunities") List<OpportunityDto> opportunities,
            @JsonProperty("productivity") ProductivityDto productivity,
            @JsonProperty("customerConfidence") CustomerConfidenceDto customerConfidence,
            @JsonProperty("metadata") Metadata metadata) {}

    /**
     * {@code customerConfidence} block (ADR 0006/0015). Emitted by the worker only for external
     * meetings (conversation with a customer/lead); null for internal meetings. {@code trend} comes
     * as the worker's guess but is ignored by the backend — the trend is recalculated server-side
     * from the account history (the backend is authoritative).
     */
    public record CustomerConfidenceDto(
            @JsonProperty("score") Integer score,
            @JsonProperty("band") String band,
            @JsonProperty("trend") String trend,
            @JsonProperty("accountName") String accountName,
            @JsonProperty("buyingSignals") List<BuyingSignalDto> buyingSignals,
            @JsonProperty("objections") List<ObjectionDto> objections,
            @JsonProperty("rationale") String rationale) {}

    public record BuyingSignalDto(
            @JsonProperty("type") String type,
            @JsonProperty("quote") String quote,
            @JsonProperty("weight") Double weight) {}

    public record ObjectionDto(
            @JsonProperty("type") String type,
            @JsonProperty("quote") String quote,
            @JsonProperty("severity") String severity,
            @JsonProperty("competitor") String competitor) {}

    public record ProductivityDto(
            @JsonProperty("score") Integer score,
            @JsonProperty("band") String band,
            @JsonProperty("coverage") List<CoverageDto> coverage,
            @JsonProperty("offTopicRatio") Double offTopicRatio,
            @JsonProperty("decisionDensity") Double decisionDensity,
            @JsonProperty("rationale") String rationale) {}

    public record CoverageDto(
            @JsonProperty("expectedOutcome") String expectedOutcome,
            @JsonProperty("status") String status,
            @JsonProperty("evidence") String evidence) {}

    public record DecisionDto(
            @JsonProperty("text") String text, @JsonProperty("confidence") Double confidence) {}

    public record ActionItemDto(
            @JsonProperty("title") String title,
            @JsonProperty("assignee") String assignee,
            @JsonProperty("dueDate") LocalDate dueDate,
            @JsonProperty("priority") String priority,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record RiskDto(
            @JsonProperty("text") String text,
            @JsonProperty("severity") String severity,
            @JsonProperty("category") String category,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record OpportunityDto(
            @JsonProperty("text") String text,
            @JsonProperty("estimatedValue") String estimatedValue,
            @JsonProperty("category") String category,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record Metadata(
            @JsonProperty("modelVersion") String modelVersion,
            @JsonProperty("promptVersion") String promptVersion,
            @JsonProperty("tokensInput") Integer tokensInput,
            @JsonProperty("tokensOutput") Integer tokensOutput,
            @JsonProperty("processingMillis") Integer processingMillis,
            @JsonProperty("piiRedactionsApplied") Integer piiRedactionsApplied) {}

    // ---------- Live Analyze (real-time analysis during a meeting) ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiveAnalyzeRequest(
            @JsonProperty("transcriptChunk") String transcriptChunk,
            @JsonProperty("previousHighlights") LiveHighlights previousHighlights,
            @JsonProperty("language") String language) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiveHighlights(
            @JsonProperty("decisions") List<LiveHighlightItem> decisions,
            @JsonProperty("nextSteps") List<LiveHighlightItem> nextSteps,
            @JsonProperty("observations") List<LiveHighlightItem> observations,
            @JsonProperty("tasks") List<LiveTaskItem> tasks) {}

    public record LiveHighlightItem(
            @JsonProperty("text") String text,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record LiveTaskItem(
            @JsonProperty("title") String title,
            @JsonProperty("assignee") String assignee,
            @JsonProperty("priority") String priority,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record LiveAnalyzeResponse(
            @JsonProperty("decisions") List<LiveHighlightItem> decisions,
            @JsonProperty("nextSteps") List<LiveHighlightItem> nextSteps,
            @JsonProperty("observations") List<LiveHighlightItem> observations,
            @JsonProperty("tasks") List<LiveTaskItem> tasks,
            @JsonProperty("metadata") LiveMetadata metadata) {}

    public record LiveMetadata(
            @JsonProperty("processingMillis") Integer processingMillis,
            @JsonProperty("tokensInput") Integer tokensInput,
            @JsonProperty("tokensOutput") Integer tokensOutput,
            @JsonProperty("piiRedactionsApplied") Integer piiRedactionsApplied,
            @JsonProperty("modelVersion") String modelVersion) {}
}
