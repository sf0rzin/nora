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
     * Contagens agregadas (action items / risks / opportunities) por meeting, em UMA query. Usa
     * {@code SIZE(...)} (subquery COUNT) — não materializa as coleções EAGER. Evita o N+1 da
     * listagem, que carregava a análise inteira (com 4 coleções) por item só pra contar.
     *
     * @return linhas {@code [meetingId, countActionItems, countRisks, countOpportunities]}
     */
    @Query(
            "SELECT a.meetingId, SIZE(a.actionItems), SIZE(a.risks), SIZE(a.opportunities) "
                    + "FROM MeetingAnalysisJpaEntity a "
                    + "WHERE a.tenantId = :tenantId AND a.meetingId IN :meetingIds")
    List<Object[]> aggregateCountsByMeetingIds(
            @Param("meetingIds") Collection<UUID> meetingIds, @Param("tenantId") UUID tenantId);
}
