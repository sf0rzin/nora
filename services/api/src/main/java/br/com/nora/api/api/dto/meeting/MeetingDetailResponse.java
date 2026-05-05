package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Detalhe da reuniao. Espelha docs/api/examples/meeting-detail-response.json (sem analysis ainda).
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
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record OwnerSummary(UUID id, String displayName) {}

    public record ParticipantPayload(String displayName, String email, boolean isInternal) {}
}
