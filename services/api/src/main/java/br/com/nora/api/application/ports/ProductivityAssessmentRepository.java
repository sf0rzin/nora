package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import java.util.Optional;
import java.util.UUID;

/** Persistencia do Productivity Assessment (ADR 0005). Sempre escopada por tenant. */
public interface ProductivityAssessmentRepository {

    ProductivityAssessment save(ProductivityAssessment assessment);

    Optional<ProductivityAssessment> findByMeetingId(UUID meetingId, UUID tenantId);

    void deleteByMeetingId(UUID meetingId, UUID tenantId);
}
