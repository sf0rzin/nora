package br.com.nora.api.application.meeting;

import br.com.nora.api.application.ports.ParticipantRepository;
import br.com.nora.api.application.ports.ParticipantRepository.ParticipantOccurrence;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import br.com.nora.api.domain.meeting.ParticipantIdentity;
import br.com.nora.api.domain.meeting.ParticipantMatcher;
import br.com.nora.api.domain.meeting.ParticipantMatcher.Occurrence;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who appears across a tenant's meetings (US13, ADR 0048).
 *
 * <p>The identities are <b>computed here on every read</b> and stored nowhere. That is the ADR's
 * retention decision and this class is where it is visible: nothing writes, so nothing can survive
 * the {@code meeting_participants} rows it was derived from. Erasing a meeting (ADR 0029) or
 * sweeping it by retention removes those rows through the V004 cascade, and the identity stops
 * being computable in the same transaction, with no orphan-cleanup path left to forget.
 *
 * <p>Every method takes a {@link Scope} the caller has already resolved against IAM. This service
 * makes no authorization decision of its own, and it must not be handed a tenant-wide scope until
 * the caller has established that the {@code meeting:read} decision is uniform across the tenant —
 * the shape {@code TrendsController} documents at length.
 */
@Service
public class ParticipantIdentityService {

    private final ParticipantRepository participants;

    public ParticipantIdentityService(ParticipantRepository participants) {
        this.participants = participants;
    }

    /** One meeting a person appears in. */
    public record MeetingRef(UUID id, String title, OffsetDateTime startedAt) {}

    /**
     * A person plus the meetings that produced them. {@code firstSeenAt} and {@code lastSeenAt} are
     * the ends of that range.
     */
    public record IdentityView(
            ParticipantIdentity identity,
            List<MeetingRef> meetings,
            OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt) {}

    /** Every distinct person inside {@code scope}, most present first. */
    public List<IdentityView> list(Scope scope) {
        List<ParticipantOccurrence> rows = participants.listOccurrences(scope);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, MeetingRef> meetingsById = new HashMap<>();
        List<Occurrence> occurrences = new ArrayList<>(rows.size());
        for (ParticipantOccurrence row : rows) {
            UUID meetingId = row.meetingId();
            MeetingRef ref = new MeetingRef(meetingId, row.meetingTitle(), row.startedAt());
            meetingsById.putIfAbsent(meetingId, ref);
            String name = row.displayName();
            occurrences.add(new Occurrence(meetingId, name, row.email(), row.internal()));
        }

        List<IdentityView> views = new ArrayList<>();
        for (ParticipantIdentity identity : ParticipantMatcher.group(occurrences)) {
            views.add(toView(identity, meetingsById));
        }
        views.sort(ParticipantIdentityService::mostPresentFirst);
        return List.copyOf(views);
    }

    private static IdentityView toView(ParticipantIdentity identity, Map<UUID, MeetingRef> byId) {
        List<MeetingRef> refs = new ArrayList<>(identity.meetingIds().size());
        for (UUID meetingId : identity.meetingIds()) {
            MeetingRef ref = byId.get(meetingId);
            if (ref != null) {
                refs.add(ref);
            }
        }
        refs.sort(ParticipantIdentityService::recentFirst);
        OffsetDateTime last = refs.isEmpty() ? null : refs.get(0).startedAt();
        OffsetDateTime first = refs.isEmpty() ? null : refs.get(refs.size() - 1).startedAt();
        return new IdentityView(identity, List.copyOf(refs), first, last);
    }

    /** Most recent meeting first, ties broken by id so the order is total. */
    private static int recentFirst(MeetingRef a, MeetingRef b) {
        int byDate = laterFirst(a.startedAt(), b.startedAt());
        return byDate != 0 ? byDate : a.id().compareTo(b.id());
    }

    /**
     * Most present person first, then most recently seen, then name, then the identity's own id.
     * Every tie is broken, so two reads of unchanged data return the same list in the same order.
     */
    private static int mostPresentFirst(IdentityView a, IdentityView b) {
        ParticipantIdentity left = a.identity();
        ParticipantIdentity right = b.identity();
        if (left.meetingCount() != right.meetingCount()) {
            return right.meetingCount() - left.meetingCount();
        }
        int byDate = laterFirst(a.lastSeenAt(), b.lastSeenAt());
        if (byDate != 0) {
            return byDate;
        }
        int byName = left.displayName().compareTo(right.displayName());
        return byName != 0 ? byName : left.id().compareTo(right.id());
    }

    /**
     * Descending by instant, nulls last. Null cannot occur on the shipped path — the column is a
     * COALESCE over a NOT NULL creation timestamp — but a comparator that throws is a bad way to
     * find that out.
     */
    private static int laterFirst(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            if (a == null && b == null) {
                return 0;
            }
            return a == null ? 1 : -1;
        }
        return b.compareTo(a);
    }
}
