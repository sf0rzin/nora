package br.com.nora.api.infrastructure.audit;

import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.IamRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default adapter: delega tudo para {@link IamRepository#recordAudit}, mantendo todos os eventos de
 * audit (IAM + auth + meeting + tenant) na mesma tabela {@code iam_audit_events}.
 */
@Component
public class AuditPortAdapter implements AuditPort {

    private final IamRepository iam;

    public AuditPortAdapter(IamRepository iam) {
        this.iam = iam;
    }

    @Override
    public void record(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload) {
        iam.recordAudit(tenantId, actorUserId, action, targetType, targetId, payload);
    }
}
