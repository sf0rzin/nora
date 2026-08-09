package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.privacy.PrivacyService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privacy/LGPD endpoints (ADR 0029). Scoped by the tenant from the JWT.
 *
 * <p>Right to be forgotten: PERMANENTLY deletes a meeting (physical hard-delete, distinct from the
 * default soft-delete of ADR 0021). The database cascade purges transcript ({@code raw_text} =
 * PII), participants, tags and analyses.
 */
@RestController
@RequestMapping("/privacy")
public class PrivacyController {

    private final PrivacyService privacy;
    private final AuthorizationService authz;

    public PrivacyController(PrivacyService privacy, AuthorizationService authz) {
        this.privacy = privacy;
        this.authz = authz;
    }

    private static String meetingResource(UUID tenantId, UUID meetingId) {
        return ResourceArns.meeting(tenantId, meetingId);
    }

    /**
     * Permanently deletes the meeting and all the linked PII. 204 on success; 404 if it does not
     * exist in the tenant (does not leak cross-tenant existence). Requires {@code meeting:update}
     * (same gate as the destructive goal removal).
     */
    @DeleteMapping("/meetings/{id}")
    public ResponseEntity<Void> eraseMeeting(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:update",
                meetingResource(principal.tenantId(), id));
        privacy.eraseMeeting(id, principal.tenantId());
        return ResponseEntity.noContent().build();
    }
}
