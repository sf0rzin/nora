package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Item of the meeting listing. Maps docs/api/examples/meetings-list-response.json.
 *
 * <p>{@code ownerName} is nullable and has always been sent as {@code null}: the listing query does
 * not join {@code users}, and the field was declared before it did. It is left in place rather than
 * removed because the response shape is part of a published contract, but the fact that it is a
 * promise nobody keeps is written down here — a client that renders it gets an empty string in
 * production and a plausible name from any fixture, which is the worst pair of behaviours to debug.
 * Filling it in means adding the join; until then, nothing should read it.
 */
public record MeetingListItem(
        UUID id,
        String title,
        OffsetDateTime startedAt,
        Long durationSeconds,
        String ownerName,
        String processingStatus,
        String summarySnippet,
        /** Every extracted action item, DONE included. */
        int actionItemCount,
        int riskCount,
        int opportunityCount,
        List<String> tags,
        /** Productivity band (LOW/MEDIUM/HIGH) when assessed; null otherwise. */
        String productivityBand,
        /** Productivity score (0-100) when assessed; null otherwise. */
        Integer productivityScore,
        /** Participant names for the avatar stack (empty when there are none). */
        List<String> participants,
        /**
         * Action items not yet DONE. This is the number to show wherever the copy says "pending" or
         * "abertos": {@link #actionItemCount} is the size of the collection and never goes down, so
         * a screen that labelled it as open work reported twelve open items for a workstream whose
         * twelve items had all been completed.
         */
        int openActionItemCount) {}
