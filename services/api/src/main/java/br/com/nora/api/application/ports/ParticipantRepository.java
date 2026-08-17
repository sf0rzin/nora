package br.com.nora.api.application.ports;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access to the declared participant rosters of a tenant's meetings (US13, ADR 0048).
 *
 * <p>This port returns <b>occurrences</b>, not people: one row per {@code meeting_participants}
 * entry, flattened with the meeting it belongs to. Deciding which occurrences denote the same
 * person is {@code ParticipantMatcher}'s job and happens in memory, on purpose — the rule folds
 * accents, drops honorifics and pt-BR particles and compares a first/last token pair, which is one
 * testable function in Java rather than a database collation nobody can unit test.
 *
 * <p>It reuses {@link TrendsRepository.Scope} rather than declaring a second record of the same
 * shape. The two endpoints answer the same authorization question — "which meetings may this caller
 * see?" — and a second copy is how the two would drift apart. Its contract is carried over intact,
 * including the part that matters: {@code meetingIds} null means the whole tenant and is only
 * correct once the caller has established that the IAM decision is uniform, while an EMPTY list
 * means nothing at all and must never widen to the tenant.
 */
public interface ParticipantRepository {

    /**
     * One roster entry, with the meeting that carries it. {@code email} is nullable, exactly as the
     * column is; {@code startedAt} falls back to the meeting's creation instant so an upload with
     * no declared start still orders.
     */
    record ParticipantOccurrence(
            UUID meetingId,
            String meetingTitle,
            OffsetDateTime startedAt,
            String displayName,
            String email,
            boolean internal) {}

    /**
     * Every roster entry visible under {@code scope}, most recent meeting first. Soft-deleted
     * meetings are excluded — native SQL does not see the entity's {@code @SQLRestriction}, and a
     * tenant-wide read that counted them would disagree with the per-item path by exactly the
     * deleted ones.
     */
    List<ParticipantOccurrence> listOccurrences(TrendsRepository.Scope scope);
}
