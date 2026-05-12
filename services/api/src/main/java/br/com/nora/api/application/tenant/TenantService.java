package br.com.nora.api.application.tenant;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.domain.tenant.Tenant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de configuracao geral do tenant (US32). Hoje cobre apenas o dominio corporativo;
 * proximas fatias podem adicionar branding, retencao, etc.
 *
 * <p>Separado de {@link TenantContextService} (este lida com produto/concorrentes/glossario do
 * subdominio "contexto comercial"; aqui e configuracao geral do tenant).
 */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final IamRepository iam;
    private final Clock clock;

    public TenantService(TenantRepository tenants, IamRepository iam, Clock clock) {
        this.tenants = tenants;
        this.iam = iam;
        this.clock = clock;
    }

    /** Le o dominio corporativo configurado para o tenant. */
    @Transactional(readOnly = true)
    public String getAllowedEmailDomain(UUID tenantId) {
        Tenant tenant =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));
        return tenant.allowedEmailDomain();
    }

    /**
     * Atualiza o dominio corporativo do tenant. {@code rawDomain} pode ser {@code null} para limpar
     * a restricao. Normaliza para lowercase + trim antes de validar via {@link
     * Tenant#isValidEmailDomain(String)}. Em caso de invalido lanca {@link
     * TenantException.InvalidDomain} (mapeada para HTTP 422). Sempre grava um {@code IamAuditEvent}
     * com action {@code tenant.domain.updated} contendo {@code oldDomain} e {@code newDomain}.
     */
    @Transactional
    public Tenant updateAllowedEmailDomain(UUID tenantId, String rawDomain, UUID actorUserId) {
        Tenant current =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));

        String normalized = Tenant.normalizeEmailDomain(rawDomain);
        if (!Tenant.isValidEmailDomain(normalized)) {
            throw new TenantException.InvalidDomain(
                    "invalid email domain: '" + rawDomain + "'. Expected format: 'example.com'.");
        }

        Tenant updated = current.withAllowedEmailDomain(normalized, clock.now());
        Tenant saved = tenants.save(updated);

        // Audit (US40): preserva oldDomain/newDomain (ambos podem ser null).
        Map<String, Object> payload = new HashMap<>();
        payload.put("oldDomain", current.allowedEmailDomain());
        payload.put("newDomain", normalized);
        iam.recordAudit(
                tenantId, actorUserId, "tenant.domain.updated", "TENANT", tenantId, payload);

        return saved;
    }
}
