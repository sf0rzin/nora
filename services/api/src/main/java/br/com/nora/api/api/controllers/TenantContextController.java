package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.tenant.TenantContextRequest;
import br.com.nora.api.api.dto.tenant.TenantContextResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.tenant.TenantContextException;
import br.com.nora.api.application.tenant.TenantContextService;
import br.com.nora.api.application.tenant.TenantContextService.ProductInput;
import br.com.nora.api.application.tenant.TenantContextService.UpsertCommand;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant context endpoints (US14/US30). 1-1 with the tenant from the JWT.
 *
 * <p>The context is injected into the LLM prompt (companyName, valueProposition, products,
 * objectionHandling). Any tenant member able to overwrite the context without a policy check was
 * effectively manipulating the LLM prompt for EVERYONE — so we require `tenant:context:read` /
 * `tenant:context:write` (ADR 0007) on {@code nora:tenant/{tenantId}:tenant/context}.
 */
@RestController
@RequestMapping("/tenant/context")
public class TenantContextController {

    private final TenantContextService service;

    public TenantContextController(TenantContextService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission(action = "tenant:context:read", resource = ResourceType.TENANT_CONTEXT)
    public TenantContextResponse get() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        TenantContext ctx =
                service.find(principal.tenantId())
                        .orElseThrow(
                                () -> new TenantContextException.NotFound(principal.tenantId()));
        return toResponse(ctx);
    }

    @PutMapping
    @RequiresPermission(action = "tenant:context:write", resource = ResourceType.TENANT_CONTEXT)
    public TenantContextResponse upsert(@Valid @RequestBody TenantContextRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        UpsertCommand cmd =
                new UpsertCommand(
                        body.companyName(),
                        body.industry(),
                        body.valueProposition(),
                        body.idealCustomerProfile(),
                        body.products() == null
                                ? List.of()
                                : body.products().stream()
                                        .map(
                                                p ->
                                                        new ProductInput(
                                                                p.name(),
                                                                p.description(),
                                                                p.keyDifferentiators()))
                                        .toList(),
                        body.competitors(),
                        body.objectionHandling());
        TenantContext saved = service.upsert(principal.tenantId(), cmd, principal.userId());
        return toResponse(saved);
    }

    private TenantContextResponse toResponse(TenantContext c) {
        return new TenantContextResponse(
                c.tenantId(),
                c.companyName(),
                c.industry(),
                c.valueProposition(),
                c.idealCustomerProfile(),
                c.products().stream()
                        .map(
                                p ->
                                        new TenantContextResponse.ProductPayload(
                                                p.name(), p.description(), p.keyDifferentiators()))
                        .toList(),
                c.competitors(),
                c.objectionHandling(),
                c.updatedAt());
    }
}
