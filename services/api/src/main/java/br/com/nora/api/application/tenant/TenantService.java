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
 * Use cases for the tenant's general configuration (US32). Today it only covers the corporate
 * domain; upcoming slices may add branding, retention, etc.
 *
 * <p>Separate from {@link TenantContextService} (that one deals with product/competitors/glossary
 * of the "commercial context" subdomain; here it is the tenant's general configuration).
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

    /** Full tenant (Workspace tab of the settings: name, slug, plan). */
    @Transactional(readOnly = true)
    public Tenant get(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new TenantException.NotFound(tenantId));
    }

    /** Renames the workspace (GOAL Phase 3). Preserves the slug; records audit with old/new. */
    @Transactional
    public Tenant rename(UUID tenantId, String newName, UUID actorUserId) {
        Tenant current =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));
        Tenant saved = tenants.save(current.withName(newName, clock.now()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("oldName", current.name());
        payload.put("newName", saved.name());
        iam.recordAudit(tenantId, actorUserId, "tenant.name.updated", "TENANT", tenantId, payload);
        return saved;
    }

    /** Reads the corporate domain configured for the tenant. */
    @Transactional(readOnly = true)
    public String getAllowedEmailDomain(UUID tenantId) {
        Tenant tenant =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));
        return tenant.allowedEmailDomain();
    }

    /**
     * Updates the tenant's corporate domain. {@code rawDomain} may be {@code null} to clear the
     * restriction. Normalizes to lowercase + trim before validating via {@link
     * Tenant#isValidEmailDomain(String)}. If invalid, throws {@link TenantException.InvalidDomain}
     * (mapped to HTTP 422). Always records an {@code IamAuditEvent} with action {@code
     * tenant.domain.updated} containing {@code oldDomain} and {@code newDomain}.
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

        // Audit (US40): preserves oldDomain/newDomain (both may be null).
        Map<String, Object> payload = new HashMap<>();
        payload.put("oldDomain", current.allowedEmailDomain());
        payload.put("newDomain", normalized);
        iam.recordAudit(
                tenantId, actorUserId, "tenant.domain.updated", "TENANT", tenantId, payload);

        return saved;
    }
}
