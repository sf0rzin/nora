package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** MeetingGoal snapshot returned in the meeting detail (ADR 0005). */
public record MeetingGoalResponse(
        UUID id,
        UUID meetingId,
        String purpose,
        List<String> expectedOutcomes,
        String projectStateSnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
