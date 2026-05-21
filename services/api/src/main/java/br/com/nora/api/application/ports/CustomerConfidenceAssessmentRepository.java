package br.com.nora.api.application.ports;

import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import java.util.List;
import java.util.UUID;

/**
 * Persistencia do Customer Confidence Assessment (ADR 0015). Sempre escopada por tenant.
 *
 * <p>1-1 por par (meeting, account): uma reuniao pode tocar mais de uma conta, logo {@code
 * findByMeetingId} retorna uma lista (no maximo um assessment por conta).
 */
public interface CustomerConfidenceAssessmentRepository {

    CustomerConfidenceAssessment save(CustomerConfidenceAssessment assessment);

    List<CustomerConfidenceAssessment> findByMeetingId(UUID meetingId, UUID tenantId);
}
