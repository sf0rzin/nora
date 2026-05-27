package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Detalhe da reuniao. Espelha docs/api/examples/meeting-detail-response.json.
 *
 * <p>Os campos {@code goal} e {@code productivity} sao opt-in (ADR 0005) e podem vir nulos quando o
 * usuario nao declarou objetivo ou quando a analise ainda nao gerou productivity. {@code
 * customerConfidence} (ADR 0015) vem nulo para reunioes internas.
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
        UUID projectId,
        String projectName,
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
