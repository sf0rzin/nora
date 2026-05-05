package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Resposta padrao apos upload (US07). Mapeia docs/api/examples/meeting-upload-response.json. */
public record MeetingUploadResponse(
        UUID id,
        UUID tenantId,
        String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        UUID ownerId,
        String processingStatus,
        OffsetDateTime createdAt) {}
