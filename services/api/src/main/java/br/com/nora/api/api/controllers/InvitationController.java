package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.auth.LoginResponse;
import br.com.nora.api.api.dto.iam.AcceptInviteRequest;
import br.com.nora.api.api.dto.iam.InviteListResponse;
import br.com.nora.api.api.dto.iam.InviteResponse;
import br.com.nora.api.api.dto.iam.InviteUserRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.iam.InvitationService;
import br.com.nora.api.application.iam.InvitationService.AcceptResult;
import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de convite por e-mail (US06, ADR 0011).
 *
 * <ul>
 *   <li>{@code POST /iam/users/invite} — cria invite, exige IAM {@code iam:user:invite}.
 *   <li>{@code POST /iam/invites/{token}/accept} — publico; cria user e devolve JWT.
 *   <li>{@code GET /iam/invites} — lista invites do tenant; exige {@code iam:invite:read}.
 *   <li>{@code DELETE /iam/invites/{id}} — revoga PENDING; exige {@code iam:invite:revoke}.
 * </ul>
 *
 * <p>O endpoint de aceite e publico porque o {@code token} e a credencial; quem possui o token
 * comprova ter acesso ao e-mail destinatario. O {@code SecurityConfig} libera o path em {@code
 * PUBLIC_ENDPOINTS}.
 */
@RestController
@RequestMapping("/iam")
public class InvitationController {

    private final InvitationService service;
    private final AuthorizationService authz;

    public InvitationController(InvitationService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    @PostMapping("/users/invite")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(@Valid @RequestBody InviteUserRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(),
                p.tenantId(),
                "iam:user:invite",
                "nora:tenant/" + p.tenantId() + ":invite/*");

        var groupIds =
                body.groupIds() == null
                        ? java.util.Set.<UUID>of()
                        : new LinkedHashSet<>(body.groupIds());
        IamInvitation invite =
                service.inviteUser(
                        p.tenantId(), p.userId(), body.email(), groupIds, body.expiresInDays());
        return toResponse(invite);
    }

    @PostMapping("/invites/{token}/accept")
    public ResponseEntity<LoginResponse> accept(
            @PathVariable("token") String token, @Valid @RequestBody AcceptInviteRequest body) {
        AcceptResult result = service.acceptInvite(token, body.displayName(), body.password());
        LoginResponse resp =
                new LoginResponse(
                        result.accessToken(),
                        "Bearer",
                        result.expiresInSeconds(),
                        result.user().id(),
                        result.user().tenantId(),
                        result.user().email().value(),
                        result.user().displayName());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/invites")
    public InviteListResponse list(
            @RequestParam(name = "status", required = false) InvitationStatus status) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(),
                p.tenantId(),
                "iam:invite:read",
                "nora:tenant/" + p.tenantId() + ":invite/*");

        List<InviteResponse> items =
                service.listInvites(p.tenantId(), status).stream()
                        .map(InvitationController::toResponse)
                        .toList();
        return new InviteListResponse(items, items.size(), 1, items.size());
    }

    @DeleteMapping("/invites/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(),
                p.tenantId(),
                "iam:invite:revoke",
                "nora:tenant/" + p.tenantId() + ":invite/" + id);
        service.revokeInvite(id, p.tenantId(), p.userId());
    }

    // ---------- mapping ----------

    private static InviteResponse toResponse(IamInvitation inv) {
        return new InviteResponse(
                inv.id(),
                inv.tenantId(),
                inv.email(),
                inv.status().name(),
                inv.invitedBy(),
                OffsetDateTime.ofInstant(inv.invitedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(inv.expiresAt(), ZoneOffset.UTC),
                List.copyOf(inv.groupIds()),
                inv.acceptedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(inv.acceptedAt(), ZoneOffset.UTC),
                inv.acceptedUserId());
    }
}
