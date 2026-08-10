package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.tenant.TenantDomainResponse;
import br.com.nora.api.api.dto.tenant.TenantDomainUpdateRequest;
import br.com.nora.api.api.dto.tenant.TenantDomainUpdateResponse;
import br.com.nora.api.api.dto.tenant.TenantNameUpdateRequest;
import br.com.nora.api.api.dto.tenant.TenantResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.tenant.TenantService;
import br.com.nora.api.domain.tenant.Tenant;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * General tenant settings endpoints (US32). Separate from {@code TenantContextController} because
 * "context" covers product/competitors/glossary (commercial subdomain) whereas tenant flags
 * (corporate domain, future branding, etc) live here.
 *
 * <p>Every operation requires an IAM permission on {@code nora:tenant/{tenantId}} — the {@code
 * TENANT} resource type of the annotation, byte-for-byte the ARN each handler used to build by
 * hand. Root has a bypass in the authorization service.
 */
@RestController
@RequestMapping("/tenant")
public class TenantController {

    private final TenantService tenants;

    public TenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    /** Current workspace (Workspace tab in settings). */
    @GetMapping
    @RequiresPermission(action = "tenant:read", resource = ResourceType.TENANT)
    public TenantResponse get() {
        AuthenticatedPrincipal p = CurrentUser.require();
        Tenant tenant = tenants.get(p.tenantId());
        return new TenantResponse(
                tenant.id(),
                tenant.name(),
                tenant.slug(),
                tenant.plan().name(),
                tenant.createdAt());
    }

    /** Renames the workspace. The slug is immutable (it lives in URLs/invites). */
    @PutMapping("/name")
    @RequiresPermission(action = "tenant:name:write", resource = ResourceType.TENANT)
    public TenantResponse rename(@Valid @RequestBody TenantNameUpdateRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        Tenant saved = tenants.rename(p.tenantId(), body.name(), p.userId());
        return new TenantResponse(
                saved.id(), saved.name(), saved.slug(), saved.plan().name(), saved.createdAt());
    }

    @GetMapping("/domain")
    @RequiresPermission(action = "tenant:domain:read", resource = ResourceType.TENANT)
    public TenantDomainResponse getDomain() {
        AuthenticatedPrincipal p = CurrentUser.require();
        return new TenantDomainResponse(p.tenantId(), tenants.getAllowedEmailDomain(p.tenantId()));
    }

    @PutMapping("/domain")
    @RequiresPermission(action = "tenant:domain:write", resource = ResourceType.TENANT)
    public TenantDomainUpdateResponse updateDomain(
            @Valid @RequestBody TenantDomainUpdateRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        Tenant saved =
                tenants.updateAllowedEmailDomain(
                        p.tenantId(), body.allowedEmailDomain(), p.userId());
        return new TenantDomainUpdateResponse(
                saved.id(), saved.allowedEmailDomain(), saved.updatedAt(), p.userId());
    }
}
