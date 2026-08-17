package br.com.nora.api.domain.meeting;

import java.util.List;
import java.util.UUID;

/**
 * One person, as recognised across the participant rosters of a tenant's meetings (US13, ADR 0048).
 *
 * <p>An identity is a <b>projection</b>, not a stored record. It is computed on read by {@link
 * ParticipantMatcher} from {@code meeting_participants} rows and is never written anywhere. That is
 * a retention decision and not a shortcut: the rows it is derived from are purged by the V004 FK
 * cascade when a meeting is erased (ADR 0029) or swept by retention, so an identity cannot outlive
 * the meetings that produced it, and there is no orphan-cleanup path that could be forgotten.
 *
 * @param id stable opaque handle — {@code sha256(anchor)} truncated to 16 hex characters, where the
 *     anchor is the e-mail when there is one and the normalised first/last name pair otherwise. It
 *     is reproducible across requests without being stored, and it deliberately does not spell a
 *     name out in an identifier.
 * @param displayName the canonical spelling — the variant carrying the most name tokens, so that
 *     "Ana Paula Silva" represents the identity that "Ana Silva" also belongs to.
 * @param email the identity's e-mail, or null when no member row declared one. There is at most one
 *     by construction: a differing e-mail vetoes a name-based merge.
 * @param internal true when ANY member row was declared internal. A person marked internal in one
 *     meeting is internal, rather than the flag flipping with whichever meeting is read last.
 * @param variants every distinct declared spelling that produced this identity, sorted. Nothing is
 *     lost to a merge — a grouping can always be inspected in the response that returns it.
 * @param meetingIds the meetings this person appears in, in the order the occurrences arrived.
 */
public record ParticipantIdentity(
        String id,
        String displayName,
        String email,
        boolean internal,
        List<String> variants,
        List<UUID> meetingIds) {

    public ParticipantIdentity {
        variants = List.copyOf(variants);
        meetingIds = List.copyOf(meetingIds);
    }

    /** How many meetings this person appears in. */
    public int meetingCount() {
        return meetingIds.size();
    }
}
