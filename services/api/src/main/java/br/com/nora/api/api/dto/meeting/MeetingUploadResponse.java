package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Standard response after upload (US07). Maps docs/api/examples/meeting-upload-response.json. */
public record MeetingUploadResponse(
        UUID id,
        UUID tenantId,
        String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        UUID ownerId,
        String processingStatus,
        OffsetDateTime createdAt) {}
