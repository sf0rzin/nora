package br.com.nora.api.infrastructure.persistence.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
     * @return rows {@code [meetingId, countActionItems, countRisks, countOpportunities]}
     */
    @Query(
            "SELECT a.meetingId, SIZE(a.actionItems), SIZE(a.risks), SIZE(a.opportunities) "
                    + "FROM MeetingAnalysisJpaEntity a "
                    + "WHERE a.tenantId = :tenantId AND a.meetingId IN :meetingIds")
    List<Object[]> aggregateCountsByMeetingIds(
            @Param("meetingIds") Collection<UUID> meetingIds, @Param("tenantId") UUID tenantId);
}
