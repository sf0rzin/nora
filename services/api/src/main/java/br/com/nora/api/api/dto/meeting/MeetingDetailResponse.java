package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Meeting detail. Mirrors docs/api/examples/meeting-detail-response.json.
 *
 * <p>The {@code goal} and {@code productivity} fields are opt-in (ADR 0005) and may come back null
 * when the user did not declare a goal or when the analysis has not produced productivity yet.
 * {@code customerConfidence} (ADR 0015) comes back null for internal meetings.
 */
public record MeetingDetailResponse(
        UUID id,
        UUID tenantId,
        String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Long durationSeconds,
        String language,
        OwnerSummary owner,
        List<ParticipantPayload> participants,
        List<String> tags,
        String processingStatus,
        Object analysis,
        MeetingGoalResponse goal,
        ProductivityAssessmentResponse productivity,
        CustomerConfidenceResponse customerConfidence,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record OwnerSummary(UUID id, String displayName) {}

    public record ParticipantPayload(String displayName, String email, boolean isInternal) {}
}
