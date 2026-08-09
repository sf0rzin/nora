package br.com.nora.api.infrastructure.persistence.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductivityAssessmentJpaRepository
        extends JpaRepository<ProductivityAssessmentJpaEntity, UUID> {

    Optional<ProductivityAssessmentJpaEntity> findByMeetingIdAndTenantId(
            UUID meetingId, UUID tenantId);

    /**
     * Productivity band + score per meeting, in ONE query (scalar projection — does not load the
     * coverage collection). For enriching the listing without N+1.
     *
     * @return rows {@code [meetingId, band, score]}
     */
    @Query(
            "SELECT p.meetingId, p.band, p.score FROM ProductivityAssessmentJpaEntity p "
                    + "WHERE p.tenantId = :tenantId AND p.meetingId IN :meetingIds")
    List<Object[]> aggregateBandsByMeetingIds(
            @Param("meetingIds") Collection<UUID> meetingIds, @Param("tenantId") UUID tenantId);

    /**
     * Deletes the assessment via native SQL so that Postgres' ON DELETE CASCADE removes the
     * coverages. Avoids the UPDATE assessment_id=NULL round that Hibernate would attempt with
     * orphanRemoval + unidirectional @JoinColumn.
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM meeting_productivity_assessments WHERE meeting_id = :meetingId "
                            + "AND tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByMeetingIdAndTenantIdNative(
            @Param("meetingId") UUID meetingId, @Param("tenantId") UUID tenantId);
}
