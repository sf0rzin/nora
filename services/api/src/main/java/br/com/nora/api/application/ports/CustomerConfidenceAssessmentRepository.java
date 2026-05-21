package br.com.nora.api.application.ports;

import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import java.util.List;
import java.util.Optional;
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

    /**
     * Avaliacao mais recente (por {@code created_at} desc) de uma conta, excluindo a reuniao
     * informada. Usado para calcular o {@code trend} server-side: compara o score novo com o da
     * ultima reuniao anterior da mesma conta. Vazio quando e a primeira reuniao da conta.
     *
     * @param accountId conta alvo
     * @param excludeMeetingId reuniao corrente (excluida para tolerar reprocessamento)
     * @param tenantId tenant dono (filtro antes do id)
     */
    Optional<CustomerConfidenceAssessment> findLatestByAccountId(
            UUID accountId, UUID excludeMeetingId, UUID tenantId);
}
