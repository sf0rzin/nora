package br.com.nora.api.infrastructure.persistence.analysis;

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
     * Apaga assessment via native SQL para que o ON DELETE CASCADE do Postgres remova as coverages.
     * Evita o ciclo de UPDATE assessment_id=NULL que o Hibernate tentaria com
     * orphanRemoval+@JoinColumn unidirecional.
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
