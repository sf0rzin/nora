package br.com.nora.api.api.dto.meeting;

import br.com.nora.api.application.meeting.ParticipantIdentityService.IdentityView;
import br.com.nora.api.application.meeting.ParticipantIdentityService.MeetingRef;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * People recognised across the tenant's meeting rosters (US13, ADR 0048).
 *
 * <p>{@code variants} is not decoration. The matching rule is fuzzy on the name side, so every
 * grouping it makes is shown with the spellings that produced it: a merge this endpoint got wrong
 * is visible in the same response that made it, rather than being something a reader has to take on
 * trust.
 */
public record ParticipantIdentityResponse(List<Item> items) {

    /** How many meetings are listed per person. The COUNT is never truncated — the list is. */
    private static final int MAX_MEETINGS_PER_IDENTITY = 10;

    /**
     * {@code id} is a stable opaque handle, reproducible across requests without being stored
     * anywhere. {@code variants} carries every declared spelling that produced this person, sorted.
     * {@code meetingCount} is the count over the whole visible set, while {@code meetings} lists
     * only the ten most recent — a person in two hundred meetings still reports two hundred.
     */
    public record Item(
            String id,
            String displayName,
            String email,
            boolean isInternal,
            List<String> variants,
            int meetingCount,
            OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt,
            List<MeetingRefPayload> meetings) {}

    public record MeetingRefPayload(UUID id, String title, OffsetDateTime startedAt) {}

    public static ParticipantIdentityResponse from(List<IdentityView> views) {
        return new ParticipantIdentityResponse(
                views.stream().map(ParticipantIdentityResponse::toItem).toList());
    }

    private static Item toItem(IdentityView view) {
        List<MeetingRefPayload> meetings =
                view.meetings().stream()
                        .limit(MAX_MEETINGS_PER_IDENTITY)
                        .map(ParticipantIdentityResponse::toMeetingRef)
                        .toList();
        return new Item(
                view.identity().id(),
                view.identity().displayName(),
                view.identity().email(),
                view.identity().internal(),
                view.identity().variants(),
                view.identity().meetingCount(),
                view.firstSeenAt(),
                view.lastSeenAt(),
                meetings);
    }

    private static MeetingRefPayload toMeetingRef(MeetingRef ref) {
        return new MeetingRefPayload(ref.id(), ref.title(), ref.startedAt());
    }
}
