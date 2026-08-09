package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Productivity Assessment persistence (ADR 0005). Always scoped by tenant. */
public interface ProductivityAssessmentRepository {

    ProductivityAssessment save(ProductivityAssessment assessment);

    Optional<ProductivityAssessment> findByMeetingId(UUID meetingId, UUID tenantId);

    void deleteByMeetingId(UUID meetingId, UUID tenantId);

    /**
     * Band + score per meeting, in ONE query (without loading coverage). For the listing without
     * N+1.
     */
    List<BandScore> bandsByMeetingIds(Collection<UUID> meetingIds, UUID tenantId);

    /** Productivity band + score of a meeting for the listing row. */
    record BandScore(UUID meetingId, String band, int score) {}
}
