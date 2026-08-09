package br.com.nora.api.application.ports;

import java.util.Map;
import java.util.UUID;

/**
 * Port for recording audit events outside the IAM context (auth, meetings, tenant settings).
 *
 * <p>It exists to decouple the identity/meeting context from {@link IamRepository}; the default
 * adapter ({@code AuditPortAdapter}) just delegates to {@code IamRepository.recordAudit}, keeping
 * all events in a single {@code iam_audit_events} table.
 *
 * <p>Bounded context: this is the cross-cutting observability concern, not part of the IAM domain.
 * auth/meeting/tenant events live here even though they are stored in the IAM schema, because they
 * are consumed by the same audit dashboard (admin view).
 */
public interface AuditPort {

    /**
     * Records an audit event.
     *
     * @param tenantId tenant the event belongs to
     * @param actorUserId who performed the action ({@code null} allowed for system/anonymous
     *     events)
     * @param action verb. Convention: {@code dominio.recurso.acao}. E.g. {@code auth.user.login},
     *     {@code meeting.deleted}, {@code tenant.settings.updated}
     * @param targetType type of the affected resource in UPPERCASE (USER, MEETING, TENANT, ...)
     * @param targetId id of the affected resource
     * @param payload arbitrary metadata serializable to JSON
     */
    void record(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload);
}
