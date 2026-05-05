package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Item da listagem de reunioes. Mapeia docs/api/examples/meetings-list-response.json. */
public record MeetingListItem(
        UUID id,
        String title,
        OffsetDateTime startedAt,
        Long durationSeconds,
        String ownerName,
        String processingStatus,
        String summarySnippet,
        int actionItemCount,
        int riskCount,
        int opportunityCount,
        List<String> tags) {}
