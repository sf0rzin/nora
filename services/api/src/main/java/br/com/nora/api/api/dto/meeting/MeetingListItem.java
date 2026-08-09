package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Item of the meeting listing. Maps docs/api/examples/meetings-list-response.json. */
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
        List<String> tags,
        /** Productivity band (LOW/MEDIUM/HIGH) when assessed; null otherwise. */
        String productivityBand,
        /** Productivity score (0-100) when assessed; null otherwise. */
        Integer productivityScore,
        /** Participant names for the avatar stack (empty when there are none). */
        List<String> participants) {}
