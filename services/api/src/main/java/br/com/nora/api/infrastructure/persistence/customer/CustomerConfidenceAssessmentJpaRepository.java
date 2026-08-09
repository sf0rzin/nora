package br.com.nora.api.infrastructure.persistence.customer;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerConfidenceAssessmentJpaRepository
        extends JpaRepository<CustomerConfidenceAssessmentJpaEntity, UUID> {

    List<CustomerConfidenceAssessmentJpaEntity> findByMeetingIdAndTenantId(
            UUID meetingId, UUID tenantId);

    /**
     * Previous assessments for an account (excludes the current meeting) ordered by {@code
     * created_at} desc. The service uses only the first (most recent) one to compute the trend;
     * using a list avoids coupling {@code Pageable}/{@code Optional} here — the thin edge stays in
     * the adapter.
     */
    List<CustomerConfidenceAssessmentJpaEntity>
            findByCustomerAccountIdAndTenantIdAndMeetingIdNotOrderByCreatedAtDesc(
                    UUID customerAccountId, UUID tenantId, UUID meetingId);

    /**
     * Deletes the assessment of a (meeting, account) pair via native SQL so that Postgres' ON
     * DELETE CASCADE removes signals/objections. Avoids the UPDATE assessment_id=NULL round that
     * Hibernate would attempt with orphanRemoval + unidirectional @JoinColumn.
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM customer_confidence_assessments WHERE meeting_id = :meetingId "
                            + "AND customer_account_id = :customerAccountId AND tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByMeetingAndAccountNative(
            @Param("meetingId") UUID meetingId,
            @Param("customerAccountId") UUID customerAccountId,
            @Param("tenantId") UUID tenantId);
}
