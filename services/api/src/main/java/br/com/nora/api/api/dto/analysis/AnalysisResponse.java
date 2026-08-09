package br.com.nora.api.api.dto.analysis;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Analysis payload returned in the meeting detail. Mirrors schema meeting-analysis-v1. */
public record AnalysisResponse(
        UUID id,
        UUID meetingId,
        String summary,
        String sentimentOverall,
        List<String> topics,
        List<DecisionDto> decisions,
        List<ActionItemDto> actionItems,
        List<RiskDto> risks,
        List<OpportunityDto> opportunities,
        Metadata metadata,
        OffsetDateTime generatedAt) {

    public record DecisionDto(String text, double confidence) {}

    public record ActionItemDto(
            String title,
            String assignee,
            LocalDate dueDate,
            String priority,
            String sourceQuote,
            String status) {}

    public record RiskDto(String text, String severity, String category, String sourceQuote) {}

    public record OpportunityDto(
            String text, String estimatedValue, String category, String sourceQuote) {}

    public record Metadata(
            String modelVersion,
            String promptVersion,
            int tokensInput,
            int tokensOutput,
            int processingMillis,
            int piiRedactionsApplied) {}
}
