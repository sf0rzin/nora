package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.tenant.TenantContextRequest;
import br.com.nora.api.api.dto.tenant.TenantContextResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.AuthorizationService;
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
 * Endpoints do contexto do tenant (US14/US30). 1-1 com o tenant do JWT.
 *
 * <p>O contexto e injetado no prompt do LLM (companyName, valueProposition, products,
 * objectionHandling). Qualquer membro do tenant que pudesse sobrescrever o contexto sem checagem de
 * policy efetivamente manipulava o prompt da LLM para TODOS — entao exigimos `tenant:context:read`
 * / `tenant:context:write` (ADR 0007).
 */
@RestController
@RequestMapping("/tenant/context")
public class TenantContextController {

    private static final String ACTION_READ = "tenant:context:read";
    private static final String ACTION_WRITE = "tenant:context:write";

    private final TenantContextService service;
    private final AuthorizationService authz;

    public TenantContextController(TenantContextService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    private static String resource(java.util.UUID tenantId) {
        return "nora:tenant/" + tenantId + ":tenant/context";
    }

    @GetMapping
    public TenantContextResponse get() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                ACTION_READ,
                resource(principal.tenantId()));
        TenantContext ctx =
                service.find(principal.tenantId())
                        .orElseThrow(
                                () -> new TenantContextException.NotFound(principal.tenantId()));
        return toResponse(ctx);
    }

    @PutMapping
    public TenantContextResponse upsert(@Valid @RequestBody TenantContextRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                ACTION_WRITE,
                resource(principal.tenantId()));
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
