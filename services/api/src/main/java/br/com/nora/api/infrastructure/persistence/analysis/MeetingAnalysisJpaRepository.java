package br.com.nora.api.infrastructure.persistence.analysis;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingAnalysisJpaRepository
        extends JpaRepository<MeetingAnalysisJpaEntity, UUID> {

    Optional<MeetingAnalysisJpaEntity> findByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);

    void deleteByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);

    /**
     * Aggregated counts (action items / risks / opportunities) per meeting, in ONE query. Uses
     * {@code SIZE(...)} (COUNT subquery) — does not materialize the EAGER collections. Avoids the
     * N+1 in the listing, which loaded the whole analysis (with 4 collections) per item just to
     * count.
     *
     * <p><b>The fourth column exists because the first one answers a different question than
     * callers assumed.</b> {@code SIZE(a.actionItems)} is the size of the collection: it counts
     * items that have been marked DONE exactly like the ones still open, and it never goes down.
     * The projects screen was labelling that number "abertos" — so a workstream whose twelve action
     * items had all been completed still reported twelve open ones, on a panel whose only job is to
     * say how much work is left. A count that only rises is the wrong number for that sentence, and
     * the right fix is a second count rather than redefining the first: the meeting detail
     * legitimately wants the total.
     *
     * <p>The open count is a correlated subquery over {@code meeting_action_items} because {@code
     * SIZE} cannot carry a predicate. {@code status <> 'DONE'} rather than {@code IN ('OPEN',
     * 'IN_PROGRESS')} so a status added later counts as open by default — the safer direction for a
     * number that means "still to do".
     *
     * @return rows {@code [meetingId, countActionItems, countRisks, countOpportunities,
     *     countOpenActionItems]}
     */
    @Query(
            "SELECT a.meetingId, SIZE(a.actionItems), SIZE(a.risks), SIZE(a.opportunities), "
                    + "(SELECT COUNT(ai) FROM ActionItemJpaEntity ai "
                    + " WHERE ai.analysisId = a.id AND ai.status <> 'DONE') "
                    + "FROM MeetingAnalysisJpaEntity a "
                    + "WHERE a.tenantId = :tenantId AND a.meetingId IN :meetingIds")
    List<Object[]> aggregateCountsByMeetingIds(
            @Param("meetingIds") Collection<UUID> meetingIds, @Param("tenantId") UUID tenantId);

    /**
     * Meetings whose analysis was generated inside a half-open window, most recent first. The
     * window a scheduled flow (US75) runs over; {@code Pageable} carries the cap that keeps a long
     * outage from turning into unbounded work.
     */
    @Query(
            "SELECT a.meetingId FROM MeetingAnalysisJpaEntity a "
                    + "WHERE a.tenantId = :tenantId AND a.generatedAt >= :from "
                    + "AND a.generatedAt < :toExclusive ORDER BY a.generatedAt DESC")
    List<UUID> findMeetingIdsAnalysedBetween(
            @Param("tenantId") UUID tenantId,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive,
            Pageable pageable);
}
