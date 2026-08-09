package br.com.nora.api.application.workflow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of the meeting/analysis data at trigger time — it is what conditions evaluate and
 * actions consume (placeholders {{meeting.title}} etc.). Built by the {@link
 * WorkflowContextFactory} from the state committed in the database. {@code sampleData} marks the
 * synthetic context used by the "Test" when the tenant does not have an analyzed meeting yet.
 */
public record WorkflowEventContext(
        UUID tenantId,
        String eventType,
        UUID meetingId,
        String meetingTitle,
        List<String> tags,
        String summary,
        String summarySnippet,
        int decisionsCount,
        int actionItemsCount,
        int risksCount,
        List<ActionItemView> actionItems,
        Integer productivityScore,
        Integer customerConfidenceScore,
        String meetingUrl,
        OffsetDateTime occurredAt,
        boolean sampleData) {

    /**
     * Minimal projection of an action item for conditions ({@code priority_equals}), templates and
     * webhook payloads ({@code dueDate} may be null — the LLM does not always extract a deadline).
     */
    public record ActionItemView(
            String title, String assignee, String priority, LocalDate dueDate) {}
}
