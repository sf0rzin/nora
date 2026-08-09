package br.com.nora.api.domain.event;

import br.com.nora.api.domain.analysis.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: an action item was extracted by a completed analysis. Emitted by {@code
 * AnalysisService.run()} AFTER the COMPLETED commit, one event PER item — workflows with the
 * matching trigger run once per action item.
 *
 * <p>Wire format of the matching trigger in Flows: {@code action_item.created}.
 */
public record ActionItemCreatedEvent(
        UUID tenantId,
        UUID meetingId,
        String title,
        String assignee,
        Priority priority,
        Instant occurredAt) {

    public ActionItemCreatedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
