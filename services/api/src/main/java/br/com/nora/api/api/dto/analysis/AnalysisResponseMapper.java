package br.com.nora.api.api.dto.analysis;

import br.com.nora.api.domain.analysis.MeetingAnalysis;

/** Maps the domain aggregate to the HTTP transport DTO. */
public final class AnalysisResponseMapper {

    private AnalysisResponseMapper() {}

    public static AnalysisResponse from(MeetingAnalysis a) {
        return new AnalysisResponse(
                a.id(),
                a.meetingId(),
                a.summary(),
                a.sentimentOverall().name(),
                a.topics(),
                a.decisions().stream()
                        .map(d -> new AnalysisResponse.DecisionDto(d.text(), d.confidence()))
                        .toList(),
                a.actionItems().stream()
                        .map(
                                ai ->
                                        new AnalysisResponse.ActionItemDto(
                                                ai.title(),
                                                ai.assignee(),
                                                ai.dueDate(),
                                                ai.priority().name(),
                                                ai.sourceQuote(),
                                                ai.status().name()))
                        .toList(),
                a.risks().stream()
                        .map(
                                r ->
                                        new AnalysisResponse.RiskDto(
                                                r.text(),
                                                r.severity().name(),
                                                r.category().name(),
                                                r.sourceQuote()))
                        .toList(),
                a.opportunities().stream()
                        .map(
                                o ->
                                        new AnalysisResponse.OpportunityDto(
                                                o.text(),
                                                o.estimatedValue().name(),
                                                o.category().name(),
                                                o.sourceQuote()))
                        .toList(),
                new AnalysisResponse.Metadata(
                        a.modelVersion(),
                        a.promptVersion(),
                        a.tokensInput(),
                        a.tokensOutput(),
                        a.processingMillis(),
                        a.piiRedactionsApplied()),
                a.generatedAt());
    }
}
