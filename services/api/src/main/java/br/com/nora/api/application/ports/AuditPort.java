package br.com.nora.api.application.ports;

import java.util.Map;
import java.util.UUID;

/**
 * Porta para registro de eventos de audit fora do contexto IAM (auth, meetings, tenant settings).
 *
 * <p>Existe pra desacoplar contexto de identidade/meeting do {@link IamRepository}; o adapter
 * default ({@code AuditPortAdapter}) apenas delega para {@code IamRepository.recordAudit}, mantendo
 * todos os eventos numa unica tabela {@code iam_audit_events}.
 *
 * <p>Bounded context: este e o cross-cutting concern de observabilidade, nao parte do IAM domain.
 * Eventos auth/meeting/tenant ficam aqui mesmo armazenados no schema IAM porque sao consumidos pelo
 * mesmo dashboard de audit (admin view).
 */
public interface AuditPort {

    /**
     * Registra um evento de audit.
     *
     * @param tenantId tenant ao qual o evento pertence
     * @param actorUserId quem fez a acao ({@code null} permitido para eventos sistema/anonimo)
     * @param action verbo. Convencao: {@code dominio.recurso.acao}. Ex: {@code auth.user.login},
     *     {@code meeting.deleted}, {@code tenant.settings.updated}
     * @param targetType tipo do recurso afetado em UPPERCASE (USER, MEETING, TENANT, ...)
     * @param targetId id do recurso afetado
     * @param payload metadados arbitrarios serializaveis em JSON
     */
    void record(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload);
}
