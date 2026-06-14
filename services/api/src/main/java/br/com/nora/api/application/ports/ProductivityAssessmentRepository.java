package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia do Productivity Assessment (ADR 0005). Sempre escopada por tenant. */
public interface ProductivityAssessmentRepository {

    ProductivityAssessment save(ProductivityAssessment assessment);

    Optional<ProductivityAssessment> findByMeetingId(UUID meetingId, UUID tenantId);

    void deleteByMeetingId(UUID meetingId, UUID tenantId);

    /** Banda + score por meeting, em UMA query (sem carregar coverage). Para a listagem sem N+1. */
    List<BandScore> bandsByMeetingIds(Collection<UUID> meetingIds, UUID tenantId);

    /** Banda + score de produtividade de um meeting para a linha da listagem. */
    record BandScore(UUID meetingId, String band, int score) {}
}
