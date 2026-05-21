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
     * Avaliacoes anteriores de uma conta (exclui a reuniao corrente) ordenadas por {@code
     * created_at} desc. O service usa apenas a primeira (mais recente) para calcular o trend; usar
     * uma lista evita acoplar {@code Pageable}/{@code Optional} aqui — a borda fina fica no
     * adapter.
     */
    List<CustomerConfidenceAssessmentJpaEntity>
            findByCustomerAccountIdAndTenantIdAndMeetingIdNotOrderByCreatedAtDesc(
                    UUID customerAccountId, UUID tenantId, UUID meetingId);

    /**
     * Apaga o assessment de um par (meeting, account) via native SQL para que o ON DELETE CASCADE
     * do Postgres remova signals/objections. Evita o ciclo de UPDATE assessment_id=NULL que o
     * Hibernate tentaria com orphanRemoval+@JoinColumn unidirecional.
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
