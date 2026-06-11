package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.tenant.TenantDomainResponse;
import br.com.nora.api.api.dto.tenant.TenantDomainUpdateRequest;
import br.com.nora.api.api.dto.tenant.TenantDomainUpdateResponse;
import br.com.nora.api.api.dto.tenant.TenantNameUpdateRequest;
import br.com.nora.api.api.dto.tenant.TenantResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.AuthorizationService;
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
 * Endpoints de configuracao geral do tenant (US32). Separado de {@code TenantContextController}
 * porque "contexto" cobre produto/concorrentes/glossario (subdominio comercial) enquanto o aqui
 * vivem flags de tenant (dominio corporativo, branding futuro, etc).
 *
 * <p>Toda operacao exige permissao IAM. Root tem bypass via {@link AuthorizationService}.
 */
@RestController
@RequestMapping("/tenant")
public class TenantController {

    private final TenantService tenants;
    private final AuthorizationService authz;

    public TenantController(TenantService tenants, AuthorizationService authz) {
        this.tenants = tenants;
        this.authz = authz;
    }

    /** Workspace atual (aba Workspace das configuracoes). */
    @GetMapping
    public TenantResponse get() {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(p.userId(), p.tenantId(), "tenant:read", resource(p));
        Tenant tenant = tenants.get(p.tenantId());
        return new TenantResponse(
                tenant.id(),
                tenant.name(),
                tenant.slug(),
                tenant.plan().name(),
                tenant.createdAt());
    }

    /** Renomeia o workspace. O slug e imutavel (vive em URLs/convites). */
    @PutMapping("/name")
    public TenantResponse rename(@Valid @RequestBody TenantNameUpdateRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(p.userId(), p.tenantId(), "tenant:name:write", resource(p));
        Tenant saved = tenants.rename(p.tenantId(), body.name(), p.userId());
        return new TenantResponse(
                saved.id(), saved.name(), saved.slug(), saved.plan().name(), saved.createdAt());
    }

    @GetMapping("/domain")
    public TenantDomainResponse getDomain() {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(p.userId(), p.tenantId(), "tenant:domain:read", resource(p));
        return new TenantDomainResponse(p.tenantId(), tenants.getAllowedEmailDomain(p.tenantId()));
    }

    @PutMapping("/domain")
    public TenantDomainUpdateResponse updateDomain(
            @Valid @RequestBody TenantDomainUpdateRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(p.userId(), p.tenantId(), "tenant:domain:write", resource(p));

        Tenant saved =
                tenants.updateAllowedEmailDomain(
                        p.tenantId(), body.allowedEmailDomain(), p.userId());
        return new TenantDomainUpdateResponse(
                saved.id(), saved.allowedEmailDomain(), saved.updatedAt(), p.userId());
    }

    private static String resource(AuthenticatedPrincipal p) {
        return "nora:tenant/" + p.tenantId();
    }
}
