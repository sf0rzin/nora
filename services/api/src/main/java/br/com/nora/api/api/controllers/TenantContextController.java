package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.tenant.TenantContextRequest;
import br.com.nora.api.api.dto.tenant.TenantContextResponse;
import br.com.nora.api.api.dto.tenant.TenantContextVersionDetailResponse;
import br.com.nora.api.api.dto.tenant.TenantContextVersionDto;
import br.com.nora.api.api.dto.tenant.TenantContextVersionListResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.tenant.TenantContextException;
import br.com.nora.api.application.tenant.TenantContextService;
import br.com.nora.api.application.tenant.TenantContextService.ProductInput;
import br.com.nora.api.application.tenant.TenantContextService.UpsertCommand;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.domain.tenant.TenantContextVersion;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant context endpoints (US14/US30) and its version history (US31). 1-1 with the tenant from the
 * JWT.
 *
 * <p>The context is injected into the LLM prompt (companyName, valueProposition, products,
 * objectionHandling). Any tenant member able to overwrite the context without a policy check was
 * effectively manipulating the LLM prompt for EVERYONE — so we require `tenant:context:read` /
 * `tenant:context:write` (ADR 0007) on {@code nora:tenant/{tenantId}:tenant/context}.
 *
 * <p>The two history handlers reuse `tenant:context:read`: reading a past version reveals nothing
 * that reading the current one does not, and inventing a third action would leave every existing
 * policy silently unable to open the trail it is meant to audit. Nothing here takes a tenant from
 * the caller — the tenant is the JWT claim, on all four handlers.
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

    /**
     * Lists the history of the tenant's context, newest first (US31).
     *
     * <p>Metadata only. This is the read path that {@code iam_policy_versions} never got: that
     * table has been written on every policy edit since V006 and has no SELECT and no endpoint
     * anywhere, which makes it a backup rather than an audit trail. A history table shipped without
     * a way to read it is the same debt under a new name.
     */
    @GetMapping("/versions")
    @RequiresPermission(action = "tenant:context:read", resource = ResourceType.TENANT_CONTEXT)
    public TenantContextVersionListResponse listVersions() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        List<TenantContextVersion> versions = service.listVersions(principal.tenantId());
        int current = versions.isEmpty() ? 0 : versions.get(0).version();
        return new TenantContextVersionListResponse(
                versions.stream().map(TenantContextController::toVersionDto).toList(),
                versions.size(),
                current);
    }

    /**
     * Reads one version of the tenant's context, with the document it froze (US31).
     *
     * <p>404 for a version this tenant does not have — including a number that exists under another
     * tenant, which must not be distinguishable from one that never existed.
     */
    @GetMapping("/versions/{version}")
    @RequiresPermission(action = "tenant:context:read", resource = ResourceType.TENANT_CONTEXT)
    public TenantContextVersionDetailResponse getVersion(@PathVariable int version) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        TenantContextVersion.Detail detail = service.findVersion(principal.tenantId(), version);
        return new TenantContextVersionDetailResponse(
                toVersionDto(detail.version()), toResponse(detail.document()));
    }

    private static TenantContextVersionDto toVersionDto(TenantContextVersion v) {
        return new TenantContextVersionDto(
                v.version(), v.createdBy(), v.createdByName(), v.createdAt());
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
