package br.com.nora.api.infrastructure.audit;

import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.IamRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default adapter: delegates everything to {@link IamRepository#recordAudit}, keeping all audit
 * events (IAM + auth + meeting + tenant) in the same {@code iam_audit_events} table.
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
