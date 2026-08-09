package br.com.nora.api.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: the analysis of a meeting finished successfully (the meeting transitioned to
 * COMPLETED and the {@code MeetingAnalysis} is already persisted). Emitted by {@code
 * AnalysisService.run()} AFTER the status transition commit — listeners (e.g. the NORA Flows
 * WorkflowEngine) can safely read the complete state from the database.
 *
 * <p>Wire format of the matching trigger in Flows: {@code meeting.analysis_completed}.
 */
public record MeetingAnalysisCompletedEvent(
        UUID tenantId, UUID meetingId, UUID analysisId, Instant occurredAt) {

    public MeetingAnalysisCompletedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
