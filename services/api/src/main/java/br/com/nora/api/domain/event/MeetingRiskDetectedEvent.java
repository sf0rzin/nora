package br.com.nora.api.domain.event;

import br.com.nora.api.domain.analysis.Severity;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: the analysis identified a HIGH severity risk in the meeting. Emitted by {@code
 * AnalysisService.run()} AFTER the COMPLETED commit, one event PER risk — and ONLY for {@code
 * Severity.HIGH} (a risk alert is an exception, not noise).
 *
 * <p>Wire format of the matching trigger in Flows: {@code meeting.risk_detected}.
 */
public record MeetingRiskDetectedEvent(
        UUID tenantId, UUID meetingId, String description, Severity severity, Instant occurredAt) {

    public MeetingRiskDetectedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
